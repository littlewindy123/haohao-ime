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
	if accessKeyID == "" || accessKeySecret == "" {
		log.Fatal("ALIYUN_ACCESS_KEY_ID and ALIYUN_ACCESS_KEY_SECRET are required")
	}

	translator, err := newAliyunTranslator(accessKeyID, accessKeySecret)
	if err != nil {
		log.Fatalf("initialize Alibaba Cloud translator: %v", err)
	}
	quota, err := newQuotaStore(
		environmentInt("HAOHAO_DAILY_REQUEST_LIMIT", 50),
		environmentInt("HAOHAO_MONTHLY_CHARACTER_LIMIT", 900_000),
		environmentString("HAOHAO_QUOTA_FILE", defaultQuotaFile),
		time.Now,
	)
	if err != nil {
		log.Fatalf("initialize quota store: %v", err)
	}

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
