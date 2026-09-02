// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestBaiduTranslatorCachesTokenAndParsesTranslation(t *testing.T) {
	var tokenCalls atomic.Int32
	var translationCalls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		switch request.URL.Path {
		case "/token":
			tokenCalls.Add(1)
			writeJSON(writer, http.StatusOK, map[string]any{"access_token": "token-1", "expires_in": 2_592_000})
		case "/translate":
			translationCalls.Add(1)
			if request.URL.Query().Get("access_token") != "token-1" {
				t.Fatalf("access token = %q", request.URL.Query().Get("access_token"))
			}
			var body map[string]string
			if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
				t.Fatal(err)
			}
			if body["q"] != "你好" || body["from"] != "zh" || body["to"] != "en" {
				t.Fatalf("body = %#v", body)
			}
			writeJSON(writer, http.StatusOK, map[string]any{
				"result": map[string]any{"trans_result": []map[string]string{{"src": "你好", "dst": "Hello"}}},
			})
		default:
			http.NotFound(writer, request)
		}
	}))
	defer server.Close()

	translator := newTestBaiduTranslator(server, time.Now)
	for index := 0; index < 2; index++ {
		translated, err := translator.Translate(context.Background(), "你好", "zh", "en")
		if err != nil || translated != "Hello" {
			t.Fatalf("translation = %q, err = %v", translated, err)
		}
	}
	if tokenCalls.Load() != 1 || translationCalls.Load() != 2 {
		t.Fatalf("token calls = %d, translation calls = %d", tokenCalls.Load(), translationCalls.Load())
	}
}

func TestBaiduTranslatorCombinesConcurrentTokenRefresh(t *testing.T) {
	var tokenCalls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		switch request.URL.Path {
		case "/token":
			tokenCalls.Add(1)
			time.Sleep(20 * time.Millisecond)
			writeJSON(writer, http.StatusOK, map[string]any{"access_token": "shared", "expires_in": 2_592_000})
		case "/translate":
			writeJSON(writer, http.StatusOK, map[string]any{
				"result": map[string]any{"trans_result": []map[string]string{{"dst": "Hello"}}},
			})
		}
	}))
	defer server.Close()

	translator := newTestBaiduTranslator(server, time.Now)
	var wait sync.WaitGroup
	for index := 0; index < 12; index++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			if _, err := translator.Translate(context.Background(), "你好", "zh", "en"); err != nil {
				t.Errorf("translate: %v", err)
			}
		}()
	}
	wait.Wait()
	if tokenCalls.Load() != 1 {
		t.Fatalf("token calls = %d", tokenCalls.Load())
	}
}

func TestBaiduTranslatorRefreshesBeforeExpiry(t *testing.T) {
	now := time.Date(2026, 9, 2, 12, 0, 0, 0, time.UTC)
	var tokenCalls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		switch request.URL.Path {
		case "/token":
			call := tokenCalls.Add(1)
			writeJSON(writer, http.StatusOK, map[string]any{"access_token": "token-" + string(rune('0'+call)), "expires_in": 172_800})
		case "/translate":
			writeJSON(writer, http.StatusOK, map[string]any{
				"result": map[string]any{"trans_result": []map[string]string{{"dst": "Hello"}}},
			})
		}
	}))
	defer server.Close()

	translator := newTestBaiduTranslator(server, func() time.Time { return now })
	if _, err := translator.Translate(context.Background(), "你好", "zh", "en"); err != nil {
		t.Fatal(err)
	}
	now = now.Add(25 * time.Hour)
	if _, err := translator.Translate(context.Background(), "你好", "zh", "en"); err != nil {
		t.Fatal(err)
	}
	if tokenCalls.Load() != 2 {
		t.Fatalf("token calls = %d", tokenCalls.Load())
	}
}

func TestBaiduTranslatorRefreshesInvalidTokenOnce(t *testing.T) {
	var tokenCalls atomic.Int32
	var translateCalls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		switch request.URL.Path {
		case "/token":
			call := tokenCalls.Add(1)
			writeJSON(writer, http.StatusOK, map[string]any{"access_token": "token-" + string(rune('0'+call)), "expires_in": 2_592_000})
		case "/translate":
			if translateCalls.Add(1) == 1 {
				writeJSON(writer, http.StatusOK, map[string]any{"error_code": "110", "error_msg": "invalid token"})
				return
			}
			writeJSON(writer, http.StatusOK, map[string]any{
				"result": map[string]any{"trans_result": []map[string]string{{"dst": "Hello"}}},
			})
		}
	}))
	defer server.Close()

	translator := newTestBaiduTranslator(server, time.Now)
	translated, err := translator.Translate(context.Background(), "你好", "zh", "en")
	if err != nil || translated != "Hello" {
		t.Fatalf("translation = %q, err = %v", translated, err)
	}
	if tokenCalls.Load() != 2 || translateCalls.Load() != 2 {
		t.Fatalf("token calls = %d, translate calls = %d", tokenCalls.Load(), translateCalls.Load())
	}
}

func TestBaiduErrorClassification(t *testing.T) {
	tests := []struct {
		code int
		want error
	}{
		{18, errProviderRateLimited},
		{19, errProviderQuota},
		{6, errProviderAuthentication},
		{20003, errProviderRejected},
		{31101, errUpstreamTimeout},
		{31103, errProviderInvalidRequest},
		{31102, errUpstreamFailure},
	}
	for _, test := range tests {
		if got := classifyBaiduError(test.code); !errors.Is(got, test.want) {
			t.Errorf("code %d = %v, want %v", test.code, got, test.want)
		}
	}
}

func newTestBaiduTranslator(server *httptest.Server, now func() time.Time) *baiduTranslator {
	translator, err := newBaiduTranslator("test-api-key", "test-secret-key", server.Client(), now)
	if err != nil {
		panic(err)
	}
	translator.tokenEndpoint = server.URL + "/token"
	translator.translationEndpoint = server.URL + "/translate"
	return translator
}
