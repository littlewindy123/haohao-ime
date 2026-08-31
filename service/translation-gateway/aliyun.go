// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"context"
	"errors"
	"strings"

	alimt "github.com/alibabacloud-go/alimt-20181012/v2/client"
	openapi "github.com/alibabacloud-go/darabonba-openapi/v2/utils"
)

var (
	errUpstreamTimeout = errors.New("upstream timeout")
	errUpstreamFailure = errors.New("upstream failure")
)

type translator interface {
	Translate(context.Context, string, string, string) (string, error)
}

type aliyunTranslator struct {
	client *alimt.Client
}

func newAliyunTranslator(accessKeyID, accessKeySecret string) (*aliyunTranslator, error) {
	config := new(openapi.Config).
		SetAccessKeyId(accessKeyID).
		SetAccessKeySecret(accessKeySecret).
		SetRegionId("cn-hangzhou").
		SetEndpoint("mt.cn-hangzhou.aliyuncs.com").
		SetConnectTimeout(2_000).
		SetReadTimeout(5_000)
	client, err := alimt.NewClient(config)
	if err != nil {
		return nil, err
	}
	return &aliyunTranslator{client: client}, nil
}

func (a *aliyunTranslator) Translate(ctx context.Context, text, sourceLanguage, targetLanguage string) (string, error) {
	type response struct {
		translation string
		err         error
	}
	result := make(chan response, 1)
	go func() {
		request := new(alimt.TranslateGeneralRequest).
			SetFormatType("text").
			SetScene("general").
			SetSourceLanguage(sourceLanguage).
			SetSourceText(text).
			SetTargetLanguage(targetLanguage)
		value, err := a.client.TranslateGeneral(request)
		if err != nil {
			result <- response{err: errUpstreamFailure}
			return
		}
		if value == nil || value.Body == nil || value.Body.Code == nil || *value.Body.Code != 200 ||
			value.Body.Data == nil || value.Body.Data.Translated == nil {
			result <- response{err: errUpstreamFailure}
			return
		}
		translation := strings.TrimSpace(*value.Body.Data.Translated)
		if translation == "" {
			result <- response{err: errUpstreamFailure}
			return
		}
		result <- response{translation: translation}
	}()

	select {
	case <-ctx.Done():
		return "", errUpstreamTimeout
	case value := <-result:
		return value.translation, value.err
	}
}
