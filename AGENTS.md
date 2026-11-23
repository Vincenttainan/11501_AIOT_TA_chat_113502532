我要使用 Android Studio 和 Java 製作「Android Socket 多人聊天室」課程專案。

需求如下：

**Android Socket Server**
- 第一頁可輸入 Server 名稱。
- 第二頁顯示 Server 已啟動及本機 IP。
- 支援多個 Client 連線。
- 顯示加入、離開聊天室的成員與聊天訊息。
- Server 可以傳送聊天訊息。

**Android Socket Client**
- 第一頁作為聊天室入口。
- 第二頁可輸入 Client 名稱、Server IP 和 Port。
- 能連線至 Server 並進行多人聊天。
- 顯示其他成員加入、離開的系統訊息。
- Client 可以正常離開聊天室，不造成 Server 崩潰。

技術要求：
- 使用 Java，不使用 Kotlin。
- 使用 XML Layout。
- 使用 TCP Socket。
- 使用 Android 原生 API。
- Server 和 Client 為兩個獨立 Android App module。
- Socket 網路操作不可阻塞 UI 主執行緒。

目前請先完成以下事項：
1. 檢查目前專案資料夾是否已有檔案。
2. 規劃完整專案架構與 Activity 分工。
3. 建議 Server 與 Client 之間的訊息格式。
4. 建立適合本專案的繁體中文 AGENTS.md。
5. 暫時不要建立或修改其他程式碼，等我確認架構後再進行實作。

請全程使用繁體中文回答。