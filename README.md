# Android Socket Chatroom & Online Paint

**Repository：** `11501_AIOT_TA_chat_113502532`<br>
**專案名稱：** Android Socket Chatroom & Online Paint

## 1. 專案簡介

本專案以 Java、Android 原生 API、XML Layout 與 TCP Socket 製作，整合多人文字聊天室和 Online Paint 即時協作畫布。Server 啟動時選擇 **Text Mode** 或 **Paint Mode**；Client 連線後由 Server 告知本次工作階段的模式，Client 不自行選擇。

## 2. 專案架構

專案包含兩個可分別執行的 Android App module：`server` 與 `client`。

| Module／類別 | 用途 |
| --- | --- |
| `server` | Android Socket Server App，監聽預設 Port `5000`。 |
| `ServerSetupActivity` | 輸入 Server 名稱並選擇 Text Mode 或 Paint Mode。 |
| `ServerRoomActivity` | 顯示文字聊天室成員與訊息，提供 Server 發訊息功能。 |
| `ServerPaintActivity` | 顯示 Paint 畫布、成員名稱／IP／顏色，提供 Clear User、Kick 和 Clear All。 |
| `ServerSession` | 管理 `ServerSocket`、Client 連線、UTF-8 JSON 收發、權威筆跡與廣播。 |
| `PaintCanvasView`（Server） | 繪製 ServerSession 提供的權威筆跡快照。 |
| `PaintStroke`、`PaintMember`（Server） | 表示筆跡資料與 Server 畫面上的成員資訊。 |
| `client` | Android Socket Client App。 |
| `ClientHomeActivity` | Client 入口畫面。 |
| `ClientRoomActivity` | 輸入 Client 名稱、Server IP、Port；顯示或傳送 Text Mode 訊息。 |
| `ClientPaintActivity` | 整合 Client Paint 畫布、Socket 事件、Undo、Eraser 與 Clear Mine。 |
| `ClientSession` | 管理單一 Client Socket 的連線生命週期、背景收發與 Activity 間事件交接。 |
| `PaintCanvasView`（Client） | 使用 Android Canvas 繪製筆跡，處理觸控與 Eraser 命中測試；不直接管理 Socket。 |
| `PaintStroke`（Client） | 表示收到或暫存於畫布的筆跡資料。 |

兩個 module 的畫面資源位於各自的 `src/main/res/layout/`，入口 Activity 與網路權限則定義於各自的 `AndroidManifest.xml`。

## 3. Text Mode 功能

- 多位 Client 可同時連線。
- Server 與 Client 都可傳送文字。
- 每則 Client 訊息會廣播給所有在線 Client，包含原始發送者；Server 訊息也會廣播給所有 Client。
- 訊息顯示發送者名稱。Server 以 join 時綁定的名稱識別該 Socket，不採信訊息中的名稱作為身分。
- 顯示在線成員，以及加入和離開通知。
- 保留原有文字聊天室的連線、雙向傳訊與多人廣播功能。

## 4. Paint Mode 功能

### Server

- 在啟動頁選擇 Paint Mode；Server 的模式固定於該工作階段。
- 顯示多人同步畫布與連線狀態。
- 為 Client 分配顏色，成員列顯示名稱、連線 IP 和顏色。
- 提供 **Clear All**、每位使用者的 **Clear User** 與 **Kick**。
- Client 正常離開、斷線或被踢出時，Server 會清理該使用者的筆跡並更新其他在線成員。
- Server 關閉 Socket 後，Client 會收到斷線狀態並返回連線畫面。

### Client

- 輸入 Client 名稱、Server IP 和 Port；IP 欄位沒有寫死 `10.0.2.2`。
- 連線後依 Server 指定的模式進入聊天室或 Paint 畫面。
- 自由繪畫並與 Server、其他 Client 同步；筆跡採用 0～1 正規化座標，可在不同尺寸畫布重繪。
- 提供 **Undo**（移除自己最後一筆仍存在的筆跡）、**Eraser**（點選自己的筆跡以整筆刪除）和 **Clear Mine**。
- 顯示在線成員及加入、離開通知；被踢出時顯示提示並返回連線畫面。
- 新 Client 加入時會接收目前畫布快照，再接收後續筆跡更新。

刪除操作由 Server 驗證 Socket 身分與筆跡擁有者；Client 不會自行修改權威畫布。Client 的 Eraser 以觸控點到筆跡線段的距離判斷命中，並使用觸碰容忍範圍，無須精準碰在線條中心。

## 5. Socket 通訊原理

Server 使用 `ServerSocket` 監聽連線，每個 Client 使用 TCP `Socket`。資料採 **UTF-8、逐行 JSON** 傳送；網路讀寫在背景執行緒進行，Activity 的畫面更新回到 Android 主執行緒。

Client 先送出 `join`。Server 回覆包含模式、Client ID 和 Paint 顏色的 `welcome`。Text Mode 訊息由 Server 廣播；Paint Mode 中 Client 送出 `stroke_submit`，Server 驗證資料後產生筆跡 ID 和 revision，並廣播 `stroke_added`。新 Client 以分段 `board_state` 載入既有筆跡；Server 透過 `stroke_removed`、`strokes_removed` 等事件同步刪除與清空結果。

常見訊息類型包括：`join`、`welcome`、`message`、`members`、`system`、`stroke_submit`、`stroke_added`、`board_state`、`undo`、`erase`、`clear_mine`、`stroke_removed`、`strokes_removed` 和 `kicked`。筆跡身分、顏色、擁有者及畫布狀態由 Server 維護。

## 6. 執行方式

### 開發環境

- Android Studio
- JDK 17
- Android SDK 35（`compileSdk`／`targetSdk` 35，`minSdk` 23）
- 專案使用 Android Gradle Plugin 8.7.3 與 Gradle Wrapper 8.9

在 Android Studio 開啟 Repository 根目錄並完成 Gradle Sync。選擇 `server` module 執行 Server App，選擇 `client` module 執行 Client App。Server 啟動時選擇模式；Client 在連線頁輸入名稱、Server IP 與 Port `5000`。

也可於專案根目錄建置 Debug APK：

```bash
./gradlew :server:assembleDebug
./gradlew :client:assembleDebug
```

Windows 可使用 `gradlew.bat`。

## 7. Android 模擬器連線教學

Android Emulator 的 `10.0.2.2` 是 Client 模擬器連到開發主機的特殊位址。若 Server 和 Client 分別執行於不同模擬器，可將主機 Port 轉送至 Server 模擬器：

1. 在 Server 模擬器啟動 Server App，選擇模式並確認正在監聽 Port `5000`。
2. 在開發主機查看已連接裝置：

   ```bash
   adb devices
   ```

3. 將主機的 TCP `5000` 轉送到 Server 模擬器的 TCP `5000`：

   ```bash
   adb -s emulator-5554 forward tcp:5000 tcp:5000
   ```

   `emulator-5554` 只是範例，請替換成 `adb devices` 顯示的實際 Server 模擬器編號。

4. 在每台 Client 模擬器輸入 Server IP `10.0.2.2` 與 Port `5000`，再連線。Client 名稱與 IP、Port 都可在畫面輸入。
5. 多台 Client 可共用此轉送設定連到同一個 Server。

檢查轉送設定：

```bash
adb forward --list
```

## 8. 功能測試結果

下表區分使用者先前回報的模擬器實測，以及近期修正後尚待再次確認的項目。此工作階段未重新操作模擬器。

| 功能 | 已知測試狀態 |
| --- | --- |
| Text Mode 多人聊天、訊息廣播 | 使用者回報實測正常。 |
| Paint Mode 兩個 Client 連線、繪畫同步、顏色區分 | 使用者回報實測正常。 |
| Undo、Clear Mine | 使用者回報 Client 畫布操作正常；Server 畫布同步修正後待重測。 |
| Clear User、Clear All、Kick | 使用者回報 Client 畫布會更新；Server 畫布同步修正後待重測。 |
| Eraser | 使用者曾回報無法刪除。程式已修正 Client 身分傳遞與命中判斷，修正後尚待實測。 |
| Client 離開、異常斷線清理筆跡 | 程式有處理流程；目前沒有本工作階段重新實測的結果。 |
| Server 關閉後 Client 返回連線畫面 | 使用者先前回報第一階段實測成功；Paint 刪除功能修正後未重新測試。 |

## 9. 已知限制

- 目前沒有登入驗證、加密傳輸或背景 Server Service；Server Activity 停止時會關閉該 Session 與連線。
- Paint 筆跡保存在 ServerSession 記憶體中，Server Session 結束後不會持久保存或還原。
- 近期 Eraser 與 Server 畫布刪除同步修正尚未在模擬器重測，請以第 8 節狀態為準。
- Undo、Eraser、Clear Mine、Clear User、Clear All 和 Kick 之外的畫布編輯工具（例如選取、移動、改色或匯出）尚未實作。
