// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const (
	defaultBaiduTokenEndpoint       = "https://aip.baidubce.com/oauth/2.0/token"
	defaultBaiduTranslationEndpoint = "https://aip.baidubce.com/rpc/2.0/mt/texttrans/v1"
	baiduTokenRefreshLead           = 24 * time.Hour
)

type baiduTranslator struct {
	apiKey              string
	secretKey           string
	client              *http.Client
	now                 func() time.Time
	tokenEndpoint       string
	translationEndpoint string
	tokenMutex          sync.Mutex
	accessToken         string
	refreshAt           time.Time
}

type baiduErrorCode int

func (c *baiduErrorCode) UnmarshalJSON(data []byte) error {
	var number int
	if err := json.Unmarshal(data, &number); err == nil {
		*c = baiduErrorCode(number)
		return nil
	}
	var text string
	if err := json.Unmarshal(data, &text); err != nil {
		return err
	}
	_, err := fmt.Sscanf(text, "%d", &number)
	if err != nil {
		return err
	}
	*c = baiduErrorCode(number)
	return nil
}

func newBaiduTranslator(apiKey, secretKey string, client *http.Client, now func() time.Time) (*baiduTranslator, error) {
	if strings.TrimSpace(apiKey) == "" || strings.TrimSpace(secretKey) == "" {
		return nil, errors.New("Baidu API key and secret key are required")
	}
	if client == nil {
		client = &http.Client{Timeout: upstreamRequestTimeout}
	}
	return &baiduTranslator{
		apiKey:              apiKey,
		secretKey:           secretKey,
		client:              client,
		now:                 now,
		tokenEndpoint:       defaultBaiduTokenEndpoint,
		translationEndpoint: defaultBaiduTranslationEndpoint,
	}, nil
}

func (b *baiduTranslator) Translate(ctx context.Context, text, sourceLanguage, targetLanguage string) (string, error) {
	var invalidToken string
	for attempt := 0; attempt < 2; attempt++ {
		token, err := b.token(ctx, invalidToken)
		if err != nil {
			return "", err
		}
		translation, errorCode, err := b.translateOnce(ctx, token, text, sourceLanguage, targetLanguage)
		if err != nil {
			return "", err
		}
		if errorCode == 0 {
			return translation, nil
		}
		if (errorCode == 110 || errorCode == 111) && attempt == 0 {
			invalidToken = token
			continue
		}
		return "", classifyBaiduError(errorCode)
	}
	return "", errProviderAuthentication
}

func (b *baiduTranslator) token(ctx context.Context, invalidToken string) (string, error) {
	b.tokenMutex.Lock()
	defer b.tokenMutex.Unlock()
	if b.accessToken != "" && b.now().Before(b.refreshAt) && (invalidToken == "" || b.accessToken != invalidToken) {
		return b.accessToken, nil
	}

	endpoint, err := url.Parse(b.tokenEndpoint)
	if err != nil {
		return "", errUpstreamFailure
	}
	query := endpoint.Query()
	query.Set("grant_type", "client_credentials")
	query.Set("client_id", b.apiKey)
	query.Set("client_secret", b.secretKey)
	endpoint.RawQuery = query.Encode()
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint.String(), nil)
	if err != nil {
		return "", errUpstreamFailure
	}
	response, err := b.client.Do(request)
	if err != nil {
		return "", classifyTransportError(ctx, err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return "", classifyHTTPError(response.StatusCode)
	}
	var payload struct {
		AccessToken      string `json:"access_token"`
		ExpiresIn        int64  `json:"expires_in"`
		Error            string `json:"error"`
		ErrorDescription string `json:"error_description"`
	}
	if err := json.NewDecoder(response.Body).Decode(&payload); err != nil {
		return "", errUpstreamFailure
	}
	if payload.Error != "" || payload.AccessToken == "" || payload.ExpiresIn <= 0 {
		return "", errProviderAuthentication
	}
	lifetime := time.Duration(payload.ExpiresIn) * time.Second
	lead := baiduTokenRefreshLead
	if lifetime <= lead {
		lead = lifetime / 10
	}
	b.accessToken = payload.AccessToken
	b.refreshAt = b.now().Add(lifetime - lead)
	return b.accessToken, nil
}

func (b *baiduTranslator) translateOnce(
	ctx context.Context,
	token, text, sourceLanguage, targetLanguage string,
) (string, int, error) {
	body, err := json.Marshal(map[string]string{
		"q":    text,
		"from": sourceLanguage,
		"to":   targetLanguage,
	})
	if err != nil {
		return "", 0, errProviderInvalidRequest
	}
	endpoint, err := url.Parse(b.translationEndpoint)
	if err != nil {
		return "", 0, errUpstreamFailure
	}
	query := endpoint.Query()
	query.Set("access_token", token)
	endpoint.RawQuery = query.Encode()
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint.String(), bytes.NewReader(body))
	if err != nil {
		return "", 0, errUpstreamFailure
	}
	request.Header.Set("Content-Type", "application/json; charset=utf-8")
	response, err := b.client.Do(request)
	if err != nil {
		return "", 0, classifyTransportError(ctx, err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return "", 0, classifyHTTPError(response.StatusCode)
	}
	var payload struct {
		ErrorCode baiduErrorCode `json:"error_code"`
		Result    struct {
			Translations []struct {
				Destination string `json:"dst"`
			} `json:"trans_result"`
		} `json:"result"`
	}
	if err := json.NewDecoder(response.Body).Decode(&payload); err != nil {
		return "", 0, errUpstreamFailure
	}
	if payload.ErrorCode != 0 {
		return "", int(payload.ErrorCode), nil
	}
	values := make([]string, 0, len(payload.Result.Translations))
	for _, item := range payload.Result.Translations {
		if value := strings.TrimSpace(item.Destination); value != "" {
			values = append(values, value)
		}
	}
	if len(values) == 0 {
		return "", 0, errUpstreamFailure
	}
	return strings.Join(values, "\n"), 0, nil
}

func classifyBaiduError(code int) error {
	switch code {
	case 18, 31104:
		return errProviderRateLimited
	case 19, 31005:
		return errProviderQuota
	case 6, 100, 110, 111:
		return errProviderAuthentication
	case 20003:
		return errProviderRejected
	case 31101:
		return errUpstreamTimeout
	case 31103, 31105, 31106, 31201, 31202, 31203, 282003, 282004:
		return errProviderInvalidRequest
	default:
		return fmt.Errorf("%w: Baidu code %d", errUpstreamFailure, code)
	}
}

func classifyTransportError(ctx context.Context, err error) error {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) || errors.Is(err, context.DeadlineExceeded) {
		return errUpstreamTimeout
	}
	return errUpstreamFailure
}

func classifyHTTPError(status int) error {
	switch {
	case status == http.StatusTooManyRequests:
		return errProviderRateLimited
	case status == http.StatusUnauthorized || status == http.StatusForbidden:
		return errProviderAuthentication
	case status >= 400 && status < 500:
		return errProviderInvalidRequest
	default:
		return errUpstreamFailure
	}
}
