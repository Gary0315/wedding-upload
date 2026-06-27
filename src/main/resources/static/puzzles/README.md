# 祝福圖

## 怎麼放圖？

把所有要當「答對成語後的祝福圖」的照片，**通通直接丟在這個資料夾裡**就好，
不用分子資料夾。副檔名支援 `.jpg / .jpeg / .png / .gif`。

例：

```
puzzles/
├─ README.md
├─ 1.jpg
├─ 2.jpg
├─ 3.png
├─ blessing-01.jpg
└─ ...
```

啟動 Spring Boot 時 `PuzzleImageCatalog` 會自己把這裡所有圖片建成索引，
之後賓客只要猜對任何一個成語，就會從這個池子裡隨機抽一張回傳給他。

## 注意事項

- 檔名建議用英文/數字（例：`1.jpg`、`blessing-01.jpg`），中文檔名雖然程式有處理 URL 編碼，
  但用 ASCII 最不容易踩到坑。
- LINE 圖片訊息有檔案大小上限（10MB），建議壓到 1MB 以內傳輸比較快。
- 圖片必須能透過你的公開 HTTPS 網址被存取到（`app.public-base-url`），
  例如 `https://abc123.ngrok.io/puzzles/1.jpg`。
- 這個資料夾完全沒圖也不會壞，使用者答對時會 fallback 成純文字祝福。
