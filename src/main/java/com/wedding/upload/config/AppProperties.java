package com.wedding.upload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 應用程式對外的公開網址（給 LINE 抓圖用）。
 *
 * <p>LINE 回傳圖片訊息必須給「公開可存取的 HTTPS 網址」，
 * 所以圖片不能用 localhost，要用 ngrok / Cloudflare Tunnel / 正式網域。
 *
 * <p>例：app.public-base-url=https://abc123.ngrok.io
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String publicBaseUrl) {
}
