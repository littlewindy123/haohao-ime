// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"bytes"
	"context"
	"errors"
	"log"
	"path/filepath"
	"testing"
	"time"
)

func TestProviderRouterUsesAliyunFirst(t *testing.T) {
	router, primary, fallback, _ := testRouter(t, quotaLimits{})
	translated, err := router.Translate(context.Background(), "你好", "zh", "en")
	if err != nil || translated != "aliyun" {
		t.Fatalf("translation = %q, err = %v", translated, err)
	}
	if primary.calls.Load() != 1 || fallback.calls.Load() != 0 {
		t.Fatalf("primary = %d, fallback = %d", primary.calls.Load(), fallback.calls.Load())
	}
}

func TestProviderRouterFallsBackOnce(t *testing.T) {
	for _, primaryError := range []error{
		errProviderQuota,
		errProviderRateLimited,
		errProviderAuthentication,
		errUpstreamTimeout,
		errUpstreamFailure,
	} {
		router, primary, fallback, logs := testRouter(t, quotaLimits{})
		primary.err = primaryError
		translated, err := router.Translate(context.Background(), "你好", "zh", "en")
		if err != nil || translated != "baidu" {
			t.Fatalf("primary error %v: translation = %q, err = %v", primaryError, translated, err)
		}
		if primary.calls.Load() > 1 || fallback.calls.Load() != 1 {
			t.Fatalf("primary error %v: primary = %d fallback = %d", primaryError, primary.calls.Load(), fallback.calls.Load())
		}
		if !bytes.Contains(logs.Bytes(), []byte("failover_reason=")) {
			t.Fatalf("primary error %v: missing failover log: %s", primaryError, logs.String())
		}
	}
}

func TestProviderRouterDoesNotRetryDeterministicErrors(t *testing.T) {
	for _, primaryError := range []error{errProviderInvalidRequest, errProviderRejected} {
		router, primary, fallback, _ := testRouter(t, quotaLimits{})
		primary.err = primaryError
		_, err := router.Translate(context.Background(), "你好", "zh", "en")
		if !errors.Is(err, primaryError) {
			t.Fatalf("error = %v, want %v", err, primaryError)
		}
		if primary.calls.Load() != 1 || fallback.calls.Load() != 0 {
			t.Fatalf("primary = %d fallback = %d", primary.calls.Load(), fallback.calls.Load())
		}
	}
}

func TestProviderRouterRetriesAliyunOnTheNextRequestAfterRecovery(t *testing.T) {
	router, primary, fallback, _ := testRouter(t, quotaLimits{})
	primary.err = errUpstreamFailure
	if translated, err := router.Translate(context.Background(), "你好", "zh", "en"); err != nil || translated != "baidu" {
		t.Fatalf("during failure = %q, %v", translated, err)
	}
	primary.err = nil
	if translated, err := router.Translate(context.Background(), "你好", "zh", "en"); err != nil || translated != "aliyun" {
		t.Fatalf("after recovery = %q, %v", translated, err)
	}
	if primary.calls.Load() != 2 || fallback.calls.Load() != 1 {
		t.Fatalf("primary = %d fallback = %d", primary.calls.Load(), fallback.calls.Load())
	}
}

func TestProviderRouterReturnsToAliyunAfterMonthReset(t *testing.T) {
	now := time.Date(2026, 9, 30, 23, 59, 0, 0, time.UTC)
	limits := defaultQuotaLimits()
	limits.AliyunMonthlyCharacters = 2
	quota, err := newQuotaStore(limits, filepath.Join(t.TempDir(), "quota.json"), func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	primary := &fakeTranslator{result: "aliyun"}
	fallback := &fakeTranslator{result: "baidu"}
	router := newProviderRouter(
		providerRoute{providerAliyun, primary, newFixedWindowLimiter(20, func() time.Time { return now })},
		providerRoute{providerBaidu, fallback, newFixedWindowLimiter(8, func() time.Time { return now })},
		quota,
		log.New(&bytes.Buffer{}, "", 0),
	)
	if translated, err := router.Translate(context.Background(), "测试", "zh", "en"); err != nil || translated != "aliyun" {
		t.Fatalf("first = %q, %v", translated, err)
	}
	if translated, err := router.Translate(context.Background(), "你", "zh", "en"); err != nil || translated != "baidu" {
		t.Fatalf("fallback = %q, %v", translated, err)
	}
	now = now.Add(2 * time.Minute)
	if translated, err := router.Translate(context.Background(), "你", "zh", "en"); err != nil || translated != "aliyun" {
		t.Fatalf("after reset = %q, %v", translated, err)
	}
}

func TestProviderRouterStopsWhenBothBudgetsAreExhausted(t *testing.T) {
	limits := defaultQuotaLimits()
	limits.AliyunMonthlyCharacters = 1
	limits.BaiduLifetimeCharacters = 1
	router, primary, fallback, _ := testRouter(t, limits)
	_, err := router.Translate(context.Background(), "测试", "zh", "en")
	if !errors.Is(err, errProviderQuota) {
		t.Fatalf("error = %v", err)
	}
	if primary.calls.Load() != 0 || fallback.calls.Load() != 0 {
		t.Fatalf("primary = %d fallback = %d", primary.calls.Load(), fallback.calls.Load())
	}
}

func TestFixedWindowLimiterHonorsProviderQPS(t *testing.T) {
	now := time.Date(2026, 9, 2, 12, 0, 0, 0, time.UTC)
	limiter := newFixedWindowLimiter(2, func() time.Time { return now })
	if !limiter.Allow() || !limiter.Allow() || limiter.Allow() {
		t.Fatal("limiter did not enforce fixed window")
	}
	now = now.Add(time.Second)
	if !limiter.Allow() {
		t.Fatal("limiter did not reset")
	}
}

func testRouter(t *testing.T, limits quotaLimits) (*providerRouter, *fakeTranslator, *fakeTranslator, *bytes.Buffer) {
	t.Helper()
	if limits == (quotaLimits{}) {
		limits = defaultQuotaLimits()
	}
	quota, err := newQuotaStore(limits, filepath.Join(t.TempDir(), "quota.json"), time.Now)
	if err != nil {
		t.Fatal(err)
	}
	primary := &fakeTranslator{result: "aliyun"}
	fallback := &fakeTranslator{result: "baidu"}
	logs := &bytes.Buffer{}
	return newProviderRouter(
		providerRoute{providerAliyun, primary, allowAllLimiter{}},
		providerRoute{providerBaidu, fallback, allowAllLimiter{}},
		quota,
		log.New(logs, "", 0),
	), primary, fallback, logs
}

type allowAllLimiter struct{}

func (allowAllLimiter) Allow() bool { return true }
