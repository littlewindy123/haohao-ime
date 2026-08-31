// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
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
	errorMonthlyQuota        = "MONTHLY_QUOTA"
	errorUpstreamTimeoutCode = "UPSTREAM_TIMEOUT"
	errorUpstreamFailureCode = "UPSTREAM_FAILURE"
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

	if err := g.quota.reserve(installID, characters); err != nil {
		switch {
		case errors.Is(err, errDailyLimit):
			status = http.StatusTooManyRequests
			writeError(writer, status, errorDailyLimit, "daily request limit reached")
		case errors.Is(err, errMonthlyQuota):
			status = http.StatusTooManyRequests
			writeError(writer, status, errorMonthlyQuota, "monthly character quota reached")
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
			if errors.Is(err, errUpstreamTimeout) || errors.Is(ctx.Err(), context.DeadlineExceeded) {
				status = http.StatusGatewayTimeout
				writeError(writer, status, errorUpstreamTimeoutCode, "translation provider timed out")
			} else {
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

var (
	errDailyLimit   = errors.New("daily request limit reached")
	errMonthlyQuota = errors.New("monthly character quota reached")
)

type quotaState struct {
	Month             string                    `json:"month"`
	MonthlyCharacters int                       `json:"monthly_characters"`
	DailyRequests     map[string]map[string]int `json:"daily_requests"`
}

type quotaStore struct {
	mutex        sync.Mutex
	dailyLimit   int
	monthlyLimit int
	path         string
	now          func() time.Time
	state        quotaState
}

func newQuotaStore(dailyLimit, monthlyLimit int, path string, now func() time.Time) (*quotaStore, error) {
	store := &quotaStore{
		dailyLimit:   dailyLimit,
		monthlyLimit: monthlyLimit,
		path:         path,
		now:          now,
		state:        quotaState{DailyRequests: make(map[string]map[string]int)},
	}
	data, err := os.ReadFile(path)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if len(data) > 0 {
		if err := json.Unmarshal(data, &store.state); err != nil {
			return nil, err
		}
		if store.state.DailyRequests == nil {
			store.state.DailyRequests = make(map[string]map[string]int)
		}
	}
	return store, nil
}

func (q *quotaStore) reserve(installID string, characters int) error {
	q.mutex.Lock()
	defer q.mutex.Unlock()

	now := q.now().UTC()
	day := now.Format("2006-01-02")
	month := now.Format("2006-01")
	next := cloneQuotaState(q.state)
	if next.Month != month {
		next.Month = month
		next.MonthlyCharacters = 0
	}
	for value := range next.DailyRequests {
		if value != day {
			delete(next.DailyRequests, value)
		}
	}
	installHash := hashInstallationID(installID)
	daily := next.DailyRequests[day]
	if daily == nil {
		daily = make(map[string]int)
		next.DailyRequests[day] = daily
	}
	if daily[installHash] >= q.dailyLimit {
		return errDailyLimit
	}
	if next.MonthlyCharacters+characters > q.monthlyLimit {
		return errMonthlyQuota
	}
	daily[installHash]++
	next.MonthlyCharacters += characters
	if err := writeQuotaState(q.path, next); err != nil {
		return err
	}
	q.state = next
	return nil
}

func cloneQuotaState(state quotaState) quotaState {
	clone := quotaState{
		Month:             state.Month,
		MonthlyCharacters: state.MonthlyCharacters,
		DailyRequests:     make(map[string]map[string]int, len(state.DailyRequests)),
	}
	for day, values := range state.DailyRequests {
		clone.DailyRequests[day] = make(map[string]int, len(values))
		for key, count := range values {
			clone.DailyRequests[day][key] = count
		}
	}
	return clone
}

func writeQuotaState(path string, state quotaState) error {
	directory := filepath.Dir(path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(directory, ".translation-quota-*.tmp")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(0o600); err != nil {
		temporary.Close()
		return err
	}
	encoder := json.NewEncoder(temporary)
	if err := encoder.Encode(state); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	if err := os.Rename(temporaryPath, path); err != nil {
		if runtime.GOOS != "windows" {
			return err
		}
		if removeErr := os.Remove(path); removeErr != nil && !errors.Is(removeErr, os.ErrNotExist) {
			return removeErr
		}
		if renameErr := os.Rename(temporaryPath, path); renameErr != nil {
			return renameErr
		}
	}
	return nil
}

func hashInstallationID(value string) string {
	digest := sha256.Sum256([]byte(value))
	return hex.EncodeToString(digest[:])
}
