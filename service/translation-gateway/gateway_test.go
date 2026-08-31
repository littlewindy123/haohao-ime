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

func TestMonthlyQuotaBoundary(t *testing.T) {
	handler, _, _ := testHandler(t, 50, 4)
	if status := sendTranslation(handler, "a", []string{"测试"}, "sentence").Code; status != http.StatusOK {
		t.Fatalf("first status = %d", status)
	}
	if status := sendTranslation(handler, "b", []string{"输入"}, "sentence").Code; status != http.StatusOK {
		t.Fatalf("boundary status = %d", status)
	}
	response := sendTranslation(handler, "c", []string{"超"}, "sentence")
	if response.Code != http.StatusTooManyRequests || !strings.Contains(response.Body.String(), errorMonthlyQuota) {
		t.Fatalf("monthly response = %d %s", response.Code, response.Body.String())
	}
}

func TestQuotaPersistsAcrossRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "quota.json")
	now := func() time.Time { return time.Date(2026, 8, 31, 12, 0, 0, 0, time.UTC) }
	first, err := newQuotaStore(1, 10, path, now)
	if err != nil {
		t.Fatal(err)
	}
	if err := first.reserve("installation", 2); err != nil {
		t.Fatal(err)
	}
	second, err := newQuotaStore(1, 10, path, now)
	if err != nil {
		t.Fatal(err)
	}
	if !errors.Is(second.reserve("installation", 1), errDailyLimit) {
		t.Fatal("restarted quota store did not preserve daily count")
	}
}

func TestConcurrentDailyQuota(t *testing.T) {
	store, err := newQuotaStore(50, 900_000, filepath.Join(t.TempDir(), "quota.json"), time.Now)
	if err != nil {
		t.Fatal(err)
	}
	var accepted atomic.Int32
	var wait sync.WaitGroup
	for index := 0; index < 100; index++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			if store.reserve("same-install", 1) == nil {
				accepted.Add(1)
			}
		}()
	}
	wait.Wait()
	if accepted.Load() != 50 {
		t.Fatalf("accepted = %d", accepted.Load())
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
	} {
		translator := &fakeTranslator{err: test.err}
		quota, err := newQuotaStore(50, 900_000, filepath.Join(t.TempDir(), "quota.json"), time.Now)
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
	quota, err := newQuotaStore(dailyLimit, monthlyLimit, filepath.Join(t.TempDir(), "quota.json"), func() time.Time {
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
