// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"context"
	"errors"
	"log"
	"sync"
	"time"
	"unicode/utf8"
)

type translator interface {
	Translate(context.Context, string, string, string) (string, error)
}

type providerName string

const (
	providerAliyun providerName = "aliyun"
	providerBaidu  providerName = "baidu"
)

var (
	errUpstreamTimeout        = errors.New("upstream timeout")
	errUpstreamFailure        = errors.New("upstream failure")
	errProviderRateLimited    = errors.New("provider rate limited")
	errProviderQuota          = errors.New("provider quota reached")
	errProviderAuthentication = errors.New("provider authentication failed")
	errProviderInvalidRequest = errors.New("provider rejected invalid request")
	errProviderRejected       = errors.New("provider rejected content")
)

type requestLimiter interface {
	Allow() bool
}

type fixedWindowLimiter struct {
	mutex       sync.Mutex
	limit       int
	now         func() time.Time
	windowStart int64
	used        int
}

func newFixedWindowLimiter(limit int, now func() time.Time) *fixedWindowLimiter {
	return &fixedWindowLimiter{limit: limit, now: now}
}

func (l *fixedWindowLimiter) Allow() bool {
	l.mutex.Lock()
	defer l.mutex.Unlock()
	window := l.now().Unix()
	if window != l.windowStart {
		l.windowStart = window
		l.used = 0
	}
	if l.used >= l.limit {
		return false
	}
	l.used++
	return true
}

type providerRoute struct {
	name       providerName
	translator translator
	limiter    requestLimiter
}

type providerRouter struct {
	primary  providerRoute
	fallback providerRoute
	quota    *quotaStore
	logger   *log.Logger
}

func newProviderRouter(primary, fallback providerRoute, quota *quotaStore, logger *log.Logger) *providerRouter {
	return &providerRouter{primary: primary, fallback: fallback, quota: quota, logger: logger}
}

func (r *providerRouter) Translate(ctx context.Context, text, sourceLanguage, targetLanguage string) (string, error) {
	characters := utf8.RuneCountInString(text)
	translation, err := r.translateWith(ctx, r.primary, text, sourceLanguage, targetLanguage, characters)
	if err == nil || !shouldFailOver(err) {
		return translation, err
	}
	r.logger.Printf(
		"translation failover_from=%s failover_to=%s failover_reason=%s chars=%d",
		r.primary.name,
		r.fallback.name,
		providerErrorLabel(err),
		characters,
	)
	return r.translateWith(ctx, r.fallback, text, sourceLanguage, targetLanguage, characters)
}

func (r *providerRouter) translateWith(
	ctx context.Context,
	route providerRoute,
	text, sourceLanguage, targetLanguage string,
	characters int,
) (string, error) {
	started := time.Now()
	if !route.limiter.Allow() {
		r.logProvider(route.name, errProviderRateLimited, started, characters)
		return "", errProviderRateLimited
	}
	if err := r.quota.reserveProvider(route.name, characters); err != nil {
		r.logProvider(route.name, err, started, characters)
		return "", err
	}
	translation, err := route.translator.Translate(ctx, text, sourceLanguage, targetLanguage)
	r.logProvider(route.name, err, started, characters)
	return translation, err
}

func (r *providerRouter) logProvider(name providerName, err error, started time.Time, characters int) {
	status := "ok"
	if err != nil {
		status = providerErrorLabel(err)
	}
	r.logger.Printf(
		"translation provider=%s status=%s duration_ms=%d chars=%d",
		name,
		status,
		time.Since(started).Milliseconds(),
		characters,
	)
}

func shouldFailOver(err error) bool {
	return errors.Is(err, errProviderQuota) ||
		errors.Is(err, errProviderRateLimited) ||
		errors.Is(err, errProviderAuthentication) ||
		errors.Is(err, errUpstreamTimeout) ||
		errors.Is(err, errUpstreamFailure)
}

func providerErrorLabel(err error) string {
	switch {
	case errors.Is(err, errProviderQuota):
		return "quota"
	case errors.Is(err, errProviderRateLimited):
		return "rate_limited"
	case errors.Is(err, errProviderAuthentication):
		return "authentication"
	case errors.Is(err, errProviderInvalidRequest):
		return "invalid_request"
	case errors.Is(err, errProviderRejected):
		return "rejected"
	case errors.Is(err, errUpstreamTimeout):
		return "timeout"
	default:
		return "failure"
	}
}
