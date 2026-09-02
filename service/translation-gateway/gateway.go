// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	installHeader            = "X-HaoHao-Install"
	maximumRequestBodyBytes  = 64 * 1024
	maximumCandidateRunes    = 16
	upstreamRequestTimeout   = 5 * time.Second
	errorInvalidRequest      = "INVALID_REQUEST"
	errorDailyLimit          = "DAILY_LIMIT"
	errorProviderQuota       = "PROVIDER_QUOTA"
	errorProviderRateLimit   = "PROVIDER_RATE_LIMIT"
	errorUpstreamTimeoutCode = "UPSTREAM_TIMEOUT"
	errorUpstreamFailureCode = "UPSTREAM_FAILURE"
	errorContentRejected     = "CONTENT_REJECTED"
)

type translateRequest struct {
	Texts          []string `json:"texts"`
	SourceLanguage string   `json:"source_lang"`
	TargetLanguage string   `json:"target_lang"`
	Purpose        string   `json:"purpose"`
	RequestID      string   `json:"request_id"`
}

type translateResponse struct {
	Translations []string `json:"translations"`
}

type errorResponse struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type gateway struct {
	translator translator
	quota      *quotaStore
	logger     *log.Logger
}

func newGatewayHandler(translator translator, quota *quotaStore, logger *log.Logger) http.Handler {
	server := &gateway{translator: translator, quota: quota, logger: logger}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", server.health)
	mux.HandleFunc("POST /v1/translate", server.translate)
	return mux
}

func (g *gateway) health(writer http.ResponseWriter, _ *http.Request) {
	writeJSON(writer, http.StatusOK, map[string]string{"status": "ok"})
}

func (g *gateway) translate(writer http.ResponseWriter, request *http.Request) {
	started := time.Now()
	status := http.StatusOK
	characters := 0
	defer func() {
		g.logger.Printf("translation status=%d duration_ms=%d chars=%d", status, time.Since(started).Milliseconds(), characters)
	}()

	installID := strings.TrimSpace(request.Header.Get(installHeader))
	if installID == "" || len(installID) > 256 {
		status = http.StatusBadRequest
		writeError(writer, status, errorInvalidRequest, "missing installation identifier")
		return
	}

	request.Body = http.MaxBytesReader(writer, request.Body, maximumRequestBodyBytes)
	decoder := json.NewDecoder(request.Body)
	decoder.DisallowUnknownFields()
	var input translateRequest
	if err := decoder.Decode(&input); err != nil {
		status = http.StatusBadRequest
		writeError(writer, status, errorInvalidRequest, "invalid JSON request")
		return
	}
	if err := ensureJSONEnd(decoder); err != nil {
		status = http.StatusBadRequest
		writeError(writer, status, errorInvalidRequest, "invalid JSON request")
		return
	}
	var valid bool
	characters, valid = validateTranslationRequest(input)
	if !valid {
		status = http.StatusBadRequest
		writeError(writer, status, errorInvalidRequest, "invalid translation request")
		return
	}

	if err := g.quota.reserveInstallation(installID, characters); err != nil {
		switch {
		case errors.Is(err, errDailyLimit), errors.Is(err, errDailyCharacterLimit):
			status = http.StatusTooManyRequests
			writeError(writer, status, errorDailyLimit, "daily translation limit reached")
		default:
			status = http.StatusInternalServerError
			writeError(writer, status, errorUpstreamFailureCode, "quota service unavailable")
		}
		return
	}

	ctx, cancel := context.WithTimeout(request.Context(), upstreamRequestTimeout)
	defer cancel()
	translations := make([]string, 0, len(input.Texts))
	for _, text := range input.Texts {
		translation, err := g.translator.Translate(ctx, text, input.SourceLanguage, input.TargetLanguage)
		if err != nil {
			switch {
			case errors.Is(err, errProviderQuota):
				status = http.StatusTooManyRequests
				writeError(writer, status, errorProviderQuota, "public translation capacity is temporarily unavailable")
			case errors.Is(err, errProviderRateLimited):
				status = http.StatusTooManyRequests
				writeError(writer, status, errorProviderRateLimit, "public translation providers are temporarily busy")
			case errors.Is(err, errProviderInvalidRequest):
				status = http.StatusBadRequest
				writeError(writer, status, errorInvalidRequest, "translation provider rejected the request")
			case errors.Is(err, errProviderRejected):
				status = http.StatusUnprocessableEntity
				writeError(writer, status, errorContentRejected, "translation provider rejected the content")
			case errors.Is(err, errUpstreamTimeout), errors.Is(ctx.Err(), context.DeadlineExceeded):
				status = http.StatusGatewayTimeout
				writeError(writer, status, errorUpstreamTimeoutCode, "translation provider timed out")
			default:
				status = http.StatusBadGateway
				writeError(writer, status, errorUpstreamFailureCode, "translation provider failed")
			}
			return
		}
		translations = append(translations, translation)
	}
	writeJSON(writer, status, translateResponse{Translations: translations})
}

func validateTranslationRequest(request translateRequest) (int, bool) {
	if request.SourceLanguage != "zh" || request.TargetLanguage != "en" || len(request.RequestID) > 128 {
		return 0, false
	}
	if len(request.Texts) == 0 || len(request.Texts) > 5 {
		return 0, false
	}
	maximumRunes := maximumCandidateRunes
	switch request.Purpose {
	case "sentence":
		if len(request.Texts) != 1 {
			return 0, false
		}
		maximumRunes = 200
	case "candidate":
	default:
		return 0, false
	}
	total := 0
	for _, text := range request.Texts {
		count := utf8.RuneCountInString(strings.TrimSpace(text))
		if count == 0 || count > maximumRunes || !utf8.ValidString(text) {
			return 0, false
		}
		total += count
	}
	return total, true
}

func ensureJSONEnd(decoder *json.Decoder) error {
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return errors.New("multiple JSON values")
	}
	return nil
}

func writeError(writer http.ResponseWriter, status int, code, message string) {
	writeJSONWithStatus(writer, status, errorResponse{Code: code, Message: message})
}

func writeJSON(writer http.ResponseWriter, status int, value any) {
	writeJSONWithStatus(writer, status, value)
}

func writeJSONWithStatus(writer http.ResponseWriter, status int, value any) {
	writer.Header().Set("Content-Type", "application/json; charset=utf-8")
	writer.Header().Set("Cache-Control", "no-store")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(value)
}
