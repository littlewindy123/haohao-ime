// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"log"
	"net/http"
	"os"
	"strconv"
	"time"
)

const (
	defaultListenAddress = "127.0.0.1:8787"
	defaultQuotaFile     = "data/translation-quota.json"
)

func main() {
	accessKeyID := os.Getenv("ALIYUN_ACCESS_KEY_ID")
	accessKeySecret := os.Getenv("ALIYUN_ACCESS_KEY_SECRET")
	baiduAPIKey := os.Getenv("BAIDU_API_KEY")
	baiduSecretKey := os.Getenv("BAIDU_SECRET_KEY")
	if accessKeyID == "" || accessKeySecret == "" || baiduAPIKey == "" || baiduSecretKey == "" {
		log.Fatal("Alibaba Cloud and Baidu Cloud translation credentials are required")
	}

	aliyun, err := newAliyunTranslator(accessKeyID, accessKeySecret)
	if err != nil {
		log.Fatalf("initialize Alibaba Cloud translator: %v", err)
	}
	baidu, err := newBaiduTranslator(baiduAPIKey, baiduSecretKey, &http.Client{Timeout: upstreamRequestTimeout}, time.Now)
	if err != nil {
		log.Fatalf("initialize Baidu Cloud translator: %v", err)
	}
	limits := defaultQuotaLimits()
	limits.DailyRequests = environmentInt("HAOHAO_DAILY_REQUEST_LIMIT", limits.DailyRequests)
	limits.DailyCharacters = environmentInt("HAOHAO_DAILY_CHARACTER_LIMIT", limits.DailyCharacters)
	limits.AliyunMonthlyCharacters = environmentIntWithLegacy(
		"HAOHAO_ALIYUN_MONTHLY_CHARACTER_LIMIT",
		"HAOHAO_MONTHLY_CHARACTER_LIMIT",
		limits.AliyunMonthlyCharacters,
	)
	limits.BaiduLifetimeCharacters = environmentInt("HAOHAO_BAIDU_LIFETIME_CHARACTER_LIMIT", limits.BaiduLifetimeCharacters)
	quota, err := newQuotaStore(
		limits,
		environmentString("HAOHAO_QUOTA_FILE", defaultQuotaFile),
		time.Now,
	)
	if err != nil {
		log.Fatalf("initialize quota store: %v", err)
	}
	translator := newProviderRouter(
		providerRoute{
			name:       providerAliyun,
			translator: aliyun,
			limiter:    newFixedWindowLimiter(environmentInt("HAOHAO_ALIYUN_QPS", 20), time.Now),
		},
		providerRoute{
			name:       providerBaidu,
			translator: baidu,
			limiter:    newFixedWindowLimiter(environmentInt("HAOHAO_BAIDU_QPS", 8), time.Now),
		},
		quota,
		log.Default(),
	)

	address := environmentString("HAOHAO_GATEWAY_ADDR", defaultListenAddress)
	server := &http.Server{
		Addr:              address,
		Handler:           newGatewayHandler(translator, quota, log.Default()),
		ReadHeaderTimeout: 3 * time.Second,
		ReadTimeout:       8 * time.Second,
		WriteTimeout:      8 * time.Second,
		IdleTimeout:       30 * time.Second,
	}
	log.Printf("translation gateway listening on %s", address)
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}

func environmentString(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func environmentInt(name string, fallback int) int {
	value, err := strconv.Atoi(os.Getenv(name))
	if err != nil || value <= 0 {
		return fallback
	}
	return value
}

func environmentIntWithLegacy(name, legacyName string, fallback int) int {
	if value := environmentInt(name, 0); value > 0 {
		return value
	}
	return environmentInt(legacyName, fallback)
}
