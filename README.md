# Android Socket 多人聊天室

以 Java、Android 原生 API、XML Layout 與 TCP Socket 製作的課程專案，分為兩個可獨立執行的 Android App：Server 和 Client。

## 目前功能

- Server 可設定聊天室名稱、啟動 TCP 監聽，並顯示本機 IP 與 Port `5000`。
- 多個 Client 可同時連線，輸入名稱、Server IP 和 Port。
- Client 加入時以名稱註冊；Server 綁定名稱與該連線，聊天室訊息採用連線已註冊的名稱。
- 名稱不分大小寫檢查重複；名稱重複或使用保留名稱 `Server` 時，Server 會拒絕該連線。
- Server 和 Client 都能傳送聊天文字；訊息會廣播給所有在線 Client，包含原始發送者。
- Server 和 Client 顯示在線成員；加入與離開時會收到系統通知及更新後的成員名單。
- Client 按下「離開聊天室」會送出離開訊息後關閉 Socket。Client 強制結束或連線發生 EOF／I/O 錯誤時，Server 會清理成員並通知其他在線 Client。
- Socket 使用 UTF-8、逐行 JSON；網路讀寫放在背景執行緒，畫面更新回到 Android 主執行緒。

Server 目前由管理畫面持有 Socket；離開或關閉 Server 管理畫面會停止監聽。專案未實作背景服務、登入驗證或加密傳輸。

## 使用技術

- Java 17 語言相容設定
- Android Gradle Plugin 8.7.3、Gradle Wrapper 8.9
- Android SDK `compileSdk 35`、`targetSdk 35`、`minSdk 23`
- Android 原生 `ServerSocket`、`Socket`、`BufferedReader`、`BufferedWriter`
- UTF-8、每行一筆 JSON 訊息

## 專案架構

```text
.
├── build.gradle                    # 共用 Gradle 外掛設定
├── settings.gradle                 # 專案與 modules 設定
├── server/                         # Server 獨立 Android App module
│   └── src/main/
│       ├── java/tw/edu/socket/server/
│       │   ├── ServerSetupActivity.java
│       │   └── ServerRoomActivity.java
│       ├── res/layout/              # Server 設定與管理畫面 XML
│       └── AndroidManifest.xml
├── client/                         # Client 獨立 Android App module
│   └── src/main/
│       ├── java/tw/edu/socket/client/
│       │   ├── ClientHomeActivity.java
│       │   └── ClientRoomActivity.java
│       ├── res/layout/              # Client 入口與聊天室 XML
│       └── AndroidManifest.xml
├── gradle/wrapper/                 # Gradle Wrapper 設定與啟動程式
└── README.md
```

## 建置

安裝 Android Studio、Android SDK 35 與 JDK 17，並在 Android Studio 中將 Gradle JDK 設為 JDK 17。於專案根目錄執行：

```bash
./gradlew :server:assembleDebug
./gradlew :client:assembleDebug
```

Windows 可使用 `gradlew.bat`。Android Studio 也可分別選取 `server` 或 `client` module 執行。

## 啟動與連線

### 一般網路或實體裝置

1. 在一台裝置啟動 Server App，進入管理畫面並確認監聽狀態。
2. 記下 Server 畫面上的本機 IP，Port 使用 `5000`。
3. 在每台 Client 輸入 Client 名稱、Server IP 和 `5000`，再按 Connect。

裝置需能透過同一網路互相連線；Client 的 IP 欄位沒有預設寫死位址。

### Android 模擬器搭配 ADB Forward

較舊版 Android Emulator 的模擬器之間通常彼此隔離，可將 Server 模擬器的 Port 轉送到開發主機，再由 Client 模擬器連到主機：

1. 在 Server 模擬器啟動 Server App，確認 Server 正在監聽 Port `5000`。
2. 在主機確認裝置序號：

   ```bash
   adb devices
   ```

3. 將主機的 TCP `5000` 轉送到 Server 模擬器的 TCP `5000`（將序號替換成 Server 模擬器的實際序號）：

   ```bash
   adb -s emulator-5554 forward tcp:5000 tcp:5000
   ```

4. 在每台 Client 模擬器輸入名稱、IP `10.0.2.2`、Port `5000`，再連線。`10.0.2.2` 是 Android Emulator 用來連到開發主機的特殊位址；Server IP 和 Port 仍可在 Client 畫面輸入。
5. 兩台或更多 Client 可使用相同的轉送端點連到該 Server。若主機的 Port `5000` 已被占用，請選擇未使用的主機 Port，並讓 Client 輸入該主機 Port；Server App 仍監聽 `5000`，轉送命令例如 `adb -s emulator-5554 forward tcp:5001 tcp:5000`。

確認轉送設定：

```bash
adb forward --list
```

## 通訊訊息概念

每筆 JSON 以換行結尾。常見訊息類型包含：

```json
{"type":"join","name":"Alice"}
{"type":"message","name":"Alice","text":"大家好"}
{"type":"leave","name":"Alice"}
{"type":"system","text":"Alice 加入聊天室"}
{"type":"members","members":["Server","Alice"]}
```

Server 會以連線註冊時綁定的名稱標記聊天訊息；接收訊息中的 `name` 欄位不會覆蓋這個名稱。
