// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type fakeTranslator struct {
	result string
	err    error
	calls  atomic.Int32
}

func (f *fakeTranslator) Translate(_ context.Context, _ string, _, _ string) (string, error) {
	f.calls.Add(1)
	if f.err != nil {
		return "", f.err
	}
	return f.result, nil
}

func TestHealth(t *testing.T) {
	handler, _, _ := testHandler(t, 50, 900_000)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if response.Code != http.StatusOK {
		t.Fatalf("health status = %d", response.Code)
	}
}

func TestDailyLimitAndNoPlaintextLogs(t *testing.T) {
	handler, translator, logs := testHandler(t, 2, 900_000)
	for index := 0; index < 2; index++ {
		if status := sendTranslation(handler, "install-a", []string{"绝不写入日志"}, "sentence").Code; status != http.StatusOK {
			t.Fatalf("request %d status = %d", index, status)
		}
	}
	response := sendTranslation(handler, "install-a", []string{"绝不写入日志"}, "sentence")
	if response.Code != http.StatusTooManyRequests || !strings.Contains(response.Body.String(), errorDailyLimit) {
		t.Fatalf("daily response = %d %s", response.Code, response.Body.String())
	}
	if translator.calls.Load() != 2 {
		t.Fatalf("translator calls = %d", translator.calls.Load())
	}
	if strings.Contains(logs.String(), "绝不写入日志") {
		t.Fatal("logs contain request text")
	}
}

func TestDailyCharacterLimitBoundary(t *testing.T) {
	handler, _, _ := testHandler(t, 50, 4)
	if status := sendTranslation(handler, "a", []string{"测试"}, "sentence").Code; status != http.StatusOK {
		t.Fatalf("first status = %d", status)
	}
	if status := sendTranslation(handler, "a", []string{"输入"}, "sentence").Code; status != http.StatusOK {
		t.Fatalf("boundary status = %d", status)
	}
	response := sendTranslation(handler, "a", []string{"超"}, "sentence")
	if response.Code != http.StatusTooManyRequests || !strings.Contains(response.Body.String(), errorDailyLimit) {
		t.Fatalf("daily character response = %d %s", response.Code, response.Body.String())
	}
}

func TestQuotaPersistsAcrossRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "quota.json")
	now := func() time.Time { return time.Date(2026, 8, 31, 12, 0, 0, 0, time.UTC) }
	limits := defaultQuotaLimits()
	limits.DailyRequests = 1
	first, err := newQuotaStore(limits, path, now)
	if err != nil {
		t.Fatal(err)
	}
	if err := first.reserveInstallation("installation", 2); err != nil {
		t.Fatal(err)
	}
	if err := first.reserveProvider(providerBaidu, 2); err != nil {
		t.Fatal(err)
	}
	second, err := newQuotaStore(limits, path, now)
	if err != nil {
		t.Fatal(err)
	}
	if !errors.Is(second.reserveInstallation("installation", 1), errDailyLimit) {
		t.Fatal("restarted quota store did not preserve daily count")
	}
	if second.state.BaiduLifetimeCharacters != 2 {
		t.Fatalf("Baidu lifetime characters = %d", second.state.BaiduLifetimeCharacters)
	}
}

func TestConcurrentDailyQuota(t *testing.T) {
	limits := defaultQuotaLimits()
	limits.DailyRequests = 50
	store, err := newQuotaStore(limits, filepath.Join(t.TempDir(), "quota.json"), time.Now)
	if err != nil {
		t.Fatal(err)
	}
	var accepted atomic.Int32
	var wait sync.WaitGroup
	for index := 0; index < 100; index++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			if store.reserveInstallation("same-install", 1) == nil {
				accepted.Add(1)
			}
		}()
	}
	wait.Wait()
	if accepted.Load() != 50 {
		t.Fatalf("accepted = %d", accepted.Load())
	}
}

func TestProviderQuotasResetAliyunButKeepBaiduLifetime(t *testing.T) {
	now := time.Date(2026, 9, 30, 23, 59, 0, 0, time.UTC)
	limits := defaultQuotaLimits()
	limits.AliyunMonthlyCharacters = 2
	limits.BaiduLifetimeCharacters = 2
	store, err := newQuotaStore(limits, filepath.Join(t.TempDir(), "quota.json"), func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	if err := store.reserveProvider(providerAliyun, 2); err != nil {
		t.Fatal(err)
	}
	if err := store.reserveProvider(providerBaidu, 2); err != nil {
		t.Fatal(err)
	}
	if !errors.Is(store.reserveProvider(providerAliyun, 1), errProviderQuota) ||
		!errors.Is(store.reserveProvider(providerBaidu, 1), errProviderQuota) {
		t.Fatal("provider caps were not enforced")
	}
	now = now.Add(2 * time.Minute)
	if err := store.reserveProvider(providerAliyun, 1); err != nil {
		t.Fatalf("Aliyun did not reset: %v", err)
	}
	if !errors.Is(store.reserveProvider(providerBaidu, 1), errProviderQuota) {
		t.Fatal("Baidu lifetime cap unexpectedly reset")
	}
}

func TestConcurrentProviderQuotaNeverExceedsBudget(t *testing.T) {
	limits := defaultQuotaLimits()
	limits.BaiduLifetimeCharacters = 25
	store, err := newQuotaStore(limits, filepath.Join(t.TempDir(), "quota.json"), time.Now)
	if err != nil {
		t.Fatal(err)
	}
	var accepted atomic.Int32
	var wait sync.WaitGroup
	for index := 0; index < 80; index++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			if store.reserveProvider(providerBaidu, 1) == nil {
				accepted.Add(1)
			}
		}()
	}
	wait.Wait()
	if accepted.Load() != 25 || store.state.BaiduLifetimeCharacters != 25 {
		t.Fatalf("accepted = %d, state = %d", accepted.Load(), store.state.BaiduLifetimeCharacters)
	}
}

func TestQuotaStateMigratesVersionOne(t *testing.T) {
	path := filepath.Join(t.TempDir(), "quota.json")
	legacy := `{"month":"2026-09","monthly_characters":7,"daily_requests":{"2026-09-02":{"hash":3}}}`
	if err := os.WriteFile(path, []byte(legacy), 0o600); err != nil {
		t.Fatal(err)
	}
	store, err := newQuotaStore(defaultQuotaLimits(), path, func() time.Time {
		return time.Date(2026, 9, 2, 12, 0, 0, 0, time.UTC)
	})
	if err != nil {
		t.Fatal(err)
	}
	if store.state.Version != quotaStateVersion || store.state.AliyunMonthlyCharacters != 7 {
		t.Fatalf("state = %#v", store.state)
	}
	if store.state.DailyUsage["2026-09-02"]["hash"].Requests != 3 {
		t.Fatalf("daily usage = %#v", store.state.DailyUsage)
	}
}

func TestRequestValidationAndCandidateBatch(t *testing.T) {
	handler, translator, _ := testHandler(t, 50, 900_000)
	valid := sendTranslation(handler, "install", []string{"鸡", "电脑"}, "candidate")
	if valid.Code != http.StatusOK || translator.calls.Load() != 2 {
		t.Fatalf("valid candidate response = %d %s", valid.Code, valid.Body.String())
	}
	invalid := sendTranslation(handler, "install", []string{"一", "二", "三", "四", "五", "六"}, "candidate")
	if invalid.Code != http.StatusBadRequest {
		t.Fatalf("invalid batch status = %d", invalid.Code)
	}
}

func TestUpstreamErrorsAreMapped(t *testing.T) {
	for _, test := range []struct {
		err    error
		status int
		code   string
	}{
		{errUpstreamTimeout, http.StatusGatewayTimeout, errorUpstreamTimeoutCode},
		{errUpstreamFailure, http.StatusBadGateway, errorUpstreamFailureCode},
		{errProviderQuota, http.StatusTooManyRequests, errorProviderQuota},
		{errProviderRateLimited, http.StatusTooManyRequests, errorProviderRateLimit},
		{errProviderInvalidRequest, http.StatusBadRequest, errorInvalidRequest},
		{errProviderRejected, http.StatusUnprocessableEntity, errorContentRejected},
	} {
		translator := &fakeTranslator{err: test.err}
		quota, err := newQuotaStore(defaultQuotaLimits(), filepath.Join(t.TempDir(), "quota.json"), time.Now)
		if err != nil {
			t.Fatal(err)
		}
		handler := newGatewayHandler(translator, quota, log.New(&bytes.Buffer{}, "", 0))
		response := sendTranslation(handler, "install", []string{"你好"}, "sentence")
		if response.Code != test.status || !strings.Contains(response.Body.String(), test.code) {
			t.Fatalf("mapped response = %d %s", response.Code, response.Body.String())
		}
	}
}

func testHandler(t *testing.T, dailyLimit, monthlyLimit int) (http.Handler, *fakeTranslator, *bytes.Buffer) {
	t.Helper()
	limits := defaultQuotaLimits()
	limits.DailyRequests = dailyLimit
	limits.DailyCharacters = monthlyLimit
	quota, err := newQuotaStore(limits, filepath.Join(t.TempDir(), "quota.json"), func() time.Time {
		return time.Date(2026, 8, 31, 12, 0, 0, 0, time.UTC)
	})
	if err != nil {
		t.Fatal(err)
	}
	translator := &fakeTranslator{result: "translation"}
	logs := &bytes.Buffer{}
	return newGatewayHandler(translator, quota, log.New(logs, "", 0)), translator, logs
}

func sendTranslation(handler http.Handler, installID string, texts []string, purpose string) *httptest.ResponseRecorder {
	payload, _ := json.Marshal(translateRequest{
		Texts: texts, SourceLanguage: "zh", TargetLanguage: "en", Purpose: purpose, RequestID: "test-request",
	})
	request := httptest.NewRequest(http.MethodPost, "/v1/translate", bytes.NewReader(payload))
	request.Header.Set(installHeader, installID)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response
}
