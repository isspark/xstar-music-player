# XSTAR

XSTAR 是一款面向 Android 的本地音乐播放器。应用以用户主动授权的本地音乐文件夹为数据来源，支持离线扫描、后台播放、播放队列、播放列表、音质识别、专辑封面和歌词功能。

应用包名：`life.xstar.music.player`

## 产品定位

- 本地优先，不要求账号登录。
- 播放核心不依赖网络。
- 只扫描用户通过系统目录选择器授权的文件夹。
- 不上传、修改、移动或删除用户的原始音乐文件。
- 在线封面和在线歌词均由用户主动触发。

## 当前功能

### 播放

- Media3 ExoPlayer 播放本地音频。
- 播放、暂停、上一首、下一首。
- 精细进度条和播放进度拖动。
- 顺序播放、随机播放、单曲循环。
- 当前播放队列查看、切换、移除和清空。
- 播放失败时提示并尝试跳过当前歌曲。
- 后台播放、锁屏和通知栏媒体控制基础支持。
- 耳机拔出暂停和音频焦点处理基础支持。

### 播放页面

- 启动后默认进入播放页面。
- 无 ActionBar、无页面标题、无底部导航栏。
- 左侧边缘向右滑动打开菜单。
- 旋转 CD 样式的圆形封面。
- 播放时封面缓慢旋转，暂停时保持角度。
- 播放时动态切换主题氛围颜色和封面缩放效果。
- 支持在设置中关闭动态节拍效果。
- 歌曲信息显示为“歌手 - 歌曲名称”。
- 歌曲名称右侧显示普通、高清、无损或 Hi-Res 音质徽标。
- 歌曲详情按钮展示完整音频参数。
- 播放页支持获取或更换当前歌曲封面。
- 点击 CD 区域可以切换到歌词页面。

### FM 模式

- 可在侧滑导航菜单中开启或关闭，默认关闭。
- 开启后保存普通播放现场，从整个音乐库生成独立随机队列并立即播放。
- 使用极简全屏黑胶画布，不显示传统控制栏和待播队列。
- 整个播放页均可单击播放或暂停、左右滑动随机换歌、长按查看歌曲详情。
- 黑胶包含唱片沟槽、内圈封面标签、边缘高光、柔和阴影和中心轴细节。
- 播放进度沿黑胶外缘形成闭合圆环，暂停时中央显示透明暂停标识。
- 标题上方显示可滚动歌词，并随播放进度高亮当前行。
- FM 切歌后自动补充缺失封面和歌词；多个在线候选默认保存第一项，且尊重在线封面和在线歌词开关。
- 应用重启后继续 FM；关闭 FM 时恢复进入前的普通队列、歌曲、位置、播放模式和播放状态。
- FM 中从音乐库主动选择歌曲会退出 FM，并按所选来源建立普通播放队列。

### 音乐库

- 使用 Storage Access Framework 的 `OpenDocumentTree` 选择音乐文件夹。
- 支持连续添加多个文件夹。
- 目录授权通过 DataStore 持久化。
- 递归扫描授权目录和子目录。
- 支持 MP3、M4A、AAC、FLAC、OGG、OPUS、WAV、WMA 等常见格式。
- 歌曲、专辑、艺术家分类浏览。
- 搜索歌曲、艺术家和专辑。
- 点击歌曲或分类后自动返回播放页面。
- 支持手动重新扫描。
- 可在设置中移除已授权文件夹。

### 音频信息

扫描时使用两套 Android API：

- `MediaMetadataRetriever`：读取标题、歌手、专辑、时长和内嵌封面。
- `MediaExtractor`：读取编码格式、码率、采样率、声道和 PCM 编码。

音质分类规则：

```text
位深 >= 24 或采样率 > 48kHz       Hi-Res
FLAC / ALAC / APE / WAV / PCM     无损
有损格式码率 >= 256kbps           高清
其他                              普通
```

### 封面

封面优先级：

```text
音乐文件内嵌封面
    ↓ 没有
用户手动通过 iTunes Search API 获取
    ↓ 没有
默认音乐符号封面
```

在线封面不会在启动或扫描时自动获取。用户可以从以下位置触发：

- 播放页歌曲详情中的“获取封面”。
- 音乐库的专辑分类详情中的“获取封面”。

iTunes 返回多个候选时，应用展示候选结果，用户选择后才下载和应用。下载后的封面保存在应用私有缓存目录，并应用到对应歌曲或专辑歌曲。

### 歌词

歌词优先级：

```text
应用私有缓存歌词
    ↓ 没有
音频同目录同名 .lrc
    ↓ 没有
用户点击“获取歌词”
    ↓
LRCLIB 在线歌词
```

支持：

- `[mm:ss.xx]` 时间标签。
- `[mm:ss:xxx]` 时间标签。
- 一行多个时间标签。
- UTF-8 BOM。
- 同步歌词当前行高亮。
- 当前歌词自动滚动。
- LRCLIB 多候选选择。
- 同步歌词和纯文本歌词。
- 在线歌词缓存到应用私有目录。
- 设置中开启或关闭在线歌词获取。
- 侧滑导航菜单中开启或关闭 FM 模式。

当前版本不直接依赖 `MediaMetadataRetriever.METADATA_KEY_LYRICS`，因为该常量并不是当前 Android SDK 的公开通用 API。`LyricsSource.Embedded` 已预留，但当前稳定可用的本地来源是同目录 LRC 和已缓存歌词。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 架构 | 单 Activity、ViewModel、Repository、单向状态流 |
| 异步 | Kotlin Coroutines、Flow、StateFlow |
| 播放 | AndroidX Media3 ExoPlayer |
| 媒体会话 | MediaSessionService、MediaSession、MediaController |
| 数据库 | Room |
| 配置存储 | DataStore Preferences |
| 文件访问 | Storage Access Framework、DocumentFile |
| 元数据 | MediaMetadataRetriever、MediaExtractor |
| 图片 | Coil、BitmapFactory |
| 在线封面 | iTunes Search API |
| 在线歌词 | LRCLIB |
| 构建 | Gradle Kotlin DSL、Android Gradle Plugin |

## 工程信息

```text
applicationId: life.xstar.music.player
namespace:     life.xstar.music.player
minSdk:        26
targetSdk:     35
compileSdk:    35
Java:          17
Kotlin:        2.0.21
AGP:           8.7.3
Media3:        1.5.1
Room:          2.6.1
```

## 目录结构

```text
.
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/life/xstar/music/player/
│       │   ├── MainActivity.kt
│       │   ├── XstarApplication.kt
│       │   ├── player/
│       │   │   ├── PlaybackService.kt
│       │   │   └── PlayerController.kt
│       │   └── data/
│       │       ├── audio/
│       │       │   ├── AudioAnalyzer.kt
│       │       │   └── AudioQuality.kt
│       │       ├── local/
│       │       │   ├── MusicDatabase.kt
│       │       │   ├── SongEntity.kt
│       │       │   ├── SongDao.kt
│       │       │   ├── PlaylistEntities.kt
│       │       │   └── PlaylistDao.kt
│       │       ├── lyrics/
│       │       │   ├── LyricsModels.kt
│       │       │   ├── LrcParser.kt
│       │       │   └── LyricsRepository.kt
│       │       ├── preferences/
│       │       │   └── PlaybackPreferences.kt
│       │       └── repository/
│       │           ├── MusicRepository.kt
│       │           └── HttpArtworkClient.kt
│       └── res/
│           ├── drawable/
│           ├── mipmap-anydpi/
│           ├── mipmap-anydpi-v26/
│           ├── values/
│           └── xml/
├── docs/
│   ├── requirements.md
│   └── requirements-analysis-design.md
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## 核心数据流

### 音乐扫描

```text
OpenDocumentTree
    ↓
持久化 tree URI 权限
    ↓
DocumentFile 递归目录
    ↓
MediaMetadataRetriever 读取标签和封面
    ↓
MediaExtractor 读取音频参数
    ↓
SongEntity
    ↓
Room upsert
    ↓
Compose 音乐库
```

### 播放

```text
Compose UI
    ↓
MusicViewModel
    ↓
PlayerController
    ↓
MediaController
    ↓
PlaybackService
    ↓
ExoPlayer
```

FM 使用与普通播放独立的持久化快照。进入 FM 前保存普通队列现场；FM 期间的进度只写入 FM 快照，不覆盖普通现场。

### 封面获取

```text
用户点击获取封面
    ↓
iTunes Search API
    ↓
候选封面列表
    ↓
用户选择
    ↓
下载并校验图片
    ↓
压缩为 JPG
    ↓
应用缓存目录
    ↓
更新 Room albumArtUri
```

### 歌词获取

```text
点击 CD
    ↓
加载缓存歌词
    ↓ 没有
查找同目录同名 LRC
    ↓ 没有
显示获取歌词按钮
    ↓
LRCLIB 查询
    ↓
用户选择候选
    ↓
保存歌词
    ↓
根据播放进度滚动
```

## 数据库

当前 Room 数据库版本为 `4`。

### `songs`

保存本地音频索引和用户播放属性：

- 稳定歌曲 ID。
- 标题、歌手、专辑。
- 音频 URI 和来源目录 URI。
- MIME 类型、编码、码率、采样率、位深和声道。
- 音质分类。
- 封面路径。
- 收藏、播放次数和最近播放时间。
- 文件名和文件大小。

### `playlists`

保存用户创建的播放列表。

### `playlist_songs`

保存播放列表和歌曲关系、排序位置，并通过外键级联删除。

### 数据库迁移

```text
1 → 2：增加 mimeType，并创建播放列表相关表
2 → 3：增加 sourceFolderUri
3 → 4：增加音频质量和文件详情字段
```

禁止使用 destructive migration 覆盖用户收藏、播放列表和播放状态。

## 权限与隐私

### Manifest 权限

- `INTERNET`：iTunes 封面和 LRCLIB 歌词请求。
- `READ_EXTERNAL_STORAGE`：旧 Android 版本兼容保留。
- `READ_MEDIA_AUDIO`：Android 13 及以上兼容声明保留。
- `POST_NOTIFICATIONS`：Android 13 及以上媒体通知。
- `FOREGROUND_SERVICE`：后台媒体播放。
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`：媒体播放前台服务类型。

当前音乐目录实际通过 SAF 授权读取，应用不使用全存储访问权限。

### 隐私约束

- 不上传本地音频文件。
- 不上传本地音频内容。
- 不修改或删除用户原始文件。
- iTunes 查询会发送艺术家和专辑信息。
- LRCLIB 查询会发送歌曲标题、艺术家、专辑和时长信息。
- 用户关闭在线封面或在线歌词后，不会发起对应网络请求。
- 在线封面和歌词保存在应用私有目录或缓存中。

## 构建

### 环境要求

- Android Studio，或已安装 Android SDK 的命令行环境。
- JDK 17。
- Android SDK Platform 35。
- Android SDK Build Tools 与当前 Android Gradle Plugin 兼容。
- Gradle 8.9 或 Android Studio 自带 Gradle。

当前项目使用的 Gradle 可执行文件示例：

```powershell
D:\Code\Tools\Gradle\gradle-8.9\bin\gradle.bat
```

### 编译 Debug APK

```powershell
D:\Code\Tools\Gradle\gradle-8.9\bin\gradle.bat :app:assembleDebug --no-daemon
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 仅编译 Kotlin

```powershell
D:\Code\Tools\Gradle\gradle-8.9\bin\gradle.bat :app:compileDebugKotlin --no-daemon
```

### Windows 构建目录锁定

如果出现无法删除 `app/build` 或 `mergeDebugResources`、`dexBuilderDebug` 目录，通常是 Gradle Daemon 或 Java 进程仍占用文件。可以执行：

```powershell
D:\Code\Tools\Gradle\gradle-8.9\bin\gradle.bat --stop
D:\Code\Tools\Gradle\gradle-8.9\bin\gradle.bat clean --no-daemon
```

如果仍被占用，关闭 Android Studio 后检查残留的 `java.exe` Gradle 进程，再重新执行构建。

## 测试重点

### 扫描和文件夹

- 选择两个或多个文件夹。
- 重新启动后验证目录授权仍存在。
- 目录包含子目录和非音频文件。
- 移除目录后验证歌曲索引清理。
- 文件名包含中文、空格和括号。
- 文件无标签或标签不完整。

### 播放

- MP3、M4A、AAC、FLAC、OGG、OPUS、WAV、WMA。
- 顺序、随机、单曲循环。
- 队列切换、移除和清空。
- 锁屏、通知栏、耳机和蓝牙控制。
- 音频焦点丢失和恢复。
- 损坏文件跳过。

### 封面

- 有内嵌封面的歌曲。
- 无内嵌封面的歌曲。
- iTunes 返回一个或多个候选。
- iTunes 无结果、超时和无网络。
- 选择封面后当前播放页是否刷新。
- 重新扫描后已有封面是否保留。

### 歌词

- 同目录同名 LRC。
- 中文和 UTF-8 BOM LRC。
- 多时间标签 LRC。
- 无本地歌词时手动获取。
- LRCLIB 返回多个候选。
- 同步歌词滚动和当前行高亮。
- 纯文本歌词不执行时间滚动。
- 关闭在线歌词后不发起网络请求。

### 音质

- 128kbps MP3：普通。
- 320kbps MP3：高清。
- 16bit/44.1kHz FLAC：无损。
- 24bit/96kHz FLAC：Hi-Res。
- WAV、AAC、未知编码的默认分类。

## 当前限制

- 当前没有正式 Release 签名配置。
- 当前没有自动化 UI 测试和完整单元测试套件。
- 当前音质分类依赖 Android 能读取到的 `MediaFormat` 参数。
- 当前节拍效果是播放状态驱动的随机动画，不是真实 PCM 或频谱分析。
- FM 随机来源仅为当前本地音乐库，不提供在线推荐或流媒体电台。
- 当前内嵌歌词没有使用通用 Android SDK API 读取，主要支持同名 LRC、缓存歌词和 LRCLIB。
- `DocumentFile` 递归扫描大型目录时可能较慢，后续可增加增量扫描、进度和取消。
- `SongEntity.mediaStoreId` 名称是早期遗留，当前实际语义是基于 URI 计算的稳定歌曲 ID。
- 封面和歌词缓存位于应用缓存或私有目录，系统清理缓存后可能需要重新获取。

## 文档

- 产品需求：[`docs/requirements.md`](docs/requirements.md)
- 需求分析与技术设计：[`docs/requirements-analysis-design.md`](docs/requirements-analysis-design.md)

两份文档应与当前代码同步维护。新增功能需要同时更新功能需求、设计落点、数据迁移、测试场景和已知限制。
