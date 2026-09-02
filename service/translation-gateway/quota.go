// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"
)

const quotaStateVersion = 2

var (
	errDailyLimit          = errors.New("daily request limit reached")
	errDailyCharacterLimit = errors.New("daily character limit reached")
)

type quotaLimits struct {
	DailyRequests           int
	DailyCharacters         int
	AliyunMonthlyCharacters int
	BaiduLifetimeCharacters int
}

func defaultQuotaLimits() quotaLimits {
	return quotaLimits{
		DailyRequests:           20,
		DailyCharacters:         300,
		AliyunMonthlyCharacters: 900_000,
		BaiduLifetimeCharacters: 4_500_000,
	}
}

type dailyQuotaUsage struct {
	Requests   int `json:"requests"`
	Characters int `json:"characters"`
}

type quotaState struct {
	Version                 int                                   `json:"version"`
	Month                   string                                `json:"month"`
	AliyunMonthlyCharacters int                                   `json:"aliyun_monthly_characters"`
	BaiduLifetimeCharacters int                                   `json:"baidu_lifetime_characters"`
	DailyUsage              map[string]map[string]dailyQuotaUsage `json:"daily_usage"`
	LegacyMonthlyCharacters int                                   `json:"monthly_characters,omitempty"`
	LegacyDailyRequests     map[string]map[string]int             `json:"daily_requests,omitempty"`
}

type quotaStore struct {
	mutex  sync.Mutex
	limits quotaLimits
	path   string
	now    func() time.Time
	state  quotaState
}

func newQuotaStore(limits quotaLimits, path string, now func() time.Time) (*quotaStore, error) {
	store := &quotaStore{
		limits: limits,
		path:   path,
		now:    now,
		state: quotaState{
			Version:    quotaStateVersion,
			DailyUsage: make(map[string]map[string]dailyQuotaUsage),
		},
	}
	data, err := os.ReadFile(path)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if len(data) > 0 {
		var loaded quotaState
		if err := json.Unmarshal(data, &loaded); err != nil {
			return nil, err
		}
		store.state = migrateQuotaState(loaded)
	}
	return store, nil
}

func (q *quotaStore) reserveInstallation(installID string, characters int) error {
	q.mutex.Lock()
	defer q.mutex.Unlock()

	now := q.now().UTC()
	day := now.Format("2006-01-02")
	next := cloneQuotaState(q.state)
	pruneDailyUsage(&next, day)
	installHash := hashInstallationID(installID)
	daily := next.DailyUsage[day]
	if daily == nil {
		daily = make(map[string]dailyQuotaUsage)
		next.DailyUsage[day] = daily
	}
	usage := daily[installHash]
	if usage.Requests >= q.limits.DailyRequests {
		return errDailyLimit
	}
	if usage.Characters+characters > q.limits.DailyCharacters {
		return errDailyCharacterLimit
	}
	usage.Requests++
	usage.Characters += characters
	daily[installHash] = usage
	return q.commit(next)
}

func (q *quotaStore) reserveProvider(provider providerName, characters int) error {
	q.mutex.Lock()
	defer q.mutex.Unlock()

	next := cloneQuotaState(q.state)
	month := q.now().UTC().Format("2006-01")
	if next.Month != month {
		next.Month = month
		next.AliyunMonthlyCharacters = 0
	}
	switch provider {
	case providerAliyun:
		if next.AliyunMonthlyCharacters+characters > q.limits.AliyunMonthlyCharacters {
			return errProviderQuota
		}
		next.AliyunMonthlyCharacters += characters
	case providerBaidu:
		if next.BaiduLifetimeCharacters+characters > q.limits.BaiduLifetimeCharacters {
			return errProviderQuota
		}
		next.BaiduLifetimeCharacters += characters
	default:
		return errUpstreamFailure
	}
	return q.commit(next)
}

func (q *quotaStore) commit(next quotaState) error {
	if err := writeQuotaState(q.path, next); err != nil {
		return err
	}
	q.state = next
	return nil
}

func migrateQuotaState(state quotaState) quotaState {
	if state.DailyUsage == nil {
		state.DailyUsage = make(map[string]map[string]dailyQuotaUsage)
	}
	if state.Version < quotaStateVersion {
		if state.AliyunMonthlyCharacters == 0 {
			state.AliyunMonthlyCharacters = state.LegacyMonthlyCharacters
		}
		for day, installs := range state.LegacyDailyRequests {
			daily := state.DailyUsage[day]
			if daily == nil {
				daily = make(map[string]dailyQuotaUsage)
				state.DailyUsage[day] = daily
			}
			for installHash, requests := range installs {
				usage := daily[installHash]
				usage.Requests = requests
				daily[installHash] = usage
			}
		}
	}
	state.Version = quotaStateVersion
	state.LegacyMonthlyCharacters = 0
	state.LegacyDailyRequests = nil
	return state
}

func pruneDailyUsage(state *quotaState, currentDay string) {
	for day := range state.DailyUsage {
		if day != currentDay {
			delete(state.DailyUsage, day)
		}
	}
}

func cloneQuotaState(state quotaState) quotaState {
	clone := quotaState{
		Version:                 quotaStateVersion,
		Month:                   state.Month,
		AliyunMonthlyCharacters: state.AliyunMonthlyCharacters,
		BaiduLifetimeCharacters: state.BaiduLifetimeCharacters,
		DailyUsage:              make(map[string]map[string]dailyQuotaUsage, len(state.DailyUsage)),
	}
	for day, values := range state.DailyUsage {
		clone.DailyUsage[day] = make(map[string]dailyQuotaUsage, len(values))
		for key, usage := range values {
			clone.DailyUsage[day][key] = usage
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
