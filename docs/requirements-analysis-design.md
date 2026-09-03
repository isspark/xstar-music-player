# XSTAR 需求分析与设计文档

## 1. 文档目的

本文档将《XSTAR 本地音乐播放器需求文档》2.0 中的需求映射为当前 Android 实现设计，作为后续开发、联调、数据库迁移和测试依据。

## 2. 设计基线

| 项目 | 方案 |
| --- | --- |
| 应用名称 | XSTAR |
| 包名与 namespace | `life.xstar.music.player` |
| 最低版本 | Android 8.0 / API 26 |
| UI | Jetpack Compose + Material 3 |
| 架构 | 单 Activity、分层架构、单向状态流 |
| 异步 | Kotlin Coroutines + Flow/StateFlow |
| 播放 | AndroidX Media3 ExoPlayer |
| 后台媒体 | MediaSessionService + MediaSession |
| 音乐来源 | Storage Access Framework 多目录授权 |
| 目录访问 | DocumentFile 递归扫描 |
| 元数据 | MediaMetadataRetriever |
| 数据库 | Room，当前 schema version 3 |
| 设置 | DataStore Preferences |
| 图片 | 内嵌封面缓存 + FileProvider + Coil |
| 在线封面 | iTunes Search API，用户主动查询 |
| 导航 | Compose ModalNavigationDrawer |
| 品牌资源 | 自适应音乐符号 Launcher 图标 |
| 歌词 | SAF 同目录 LRC + LRCLIB + 应用私有缓存 |
| 音频质量 | MediaExtractor 音轨参数分析 + AudioQualityClassifier |

## 3. 当前产品决策

- 启动后直接进入全屏播放页。
- 不使用 ActionBar、顶部标题和底部导航。
- 从屏幕左边缘右滑打开抽屉菜单，同时保留菜单按钮。
- 不进行全设备 MediaStore 扫描，只读取用户选择的一个或多个文件夹。
- 文件夹使用 SAF 长期只读 URI 授权，配置持久化在 DataStore。
- 播放页采用正圆形内嵌封面、动态氛围背景和水平播放控制。
- 用户可以关闭节拍动态效果，并选择主题色。
- 播放核心离线可用；缺失封面时由用户在专辑详情中主动使用在线服务，不修改用户文件。

## 4. 领域模型

```text
Song             授权目录中的本地音频及用户属性
SourceFolder     用户授权的 SAF tree URI
Album            由 Song.album 聚合得到
Artist           由 Song.artist 聚合得到
Playlist         用户持久化播放列表
PlaylistSong     播放列表与歌曲关系及位置
Queue            Media3 当前临时播放上下文
PlayerState      当前歌曲、进度、模式、队列和错误
PlaybackPrefs    队列恢复数据
ThemeAccent      主题色枚举
BeatEffect       节拍动态效果开关
FmMode           独立随机电台状态与极简播放界面
```

Album 和 Artist 不单独建表，通过 `songs` 聚合，避免目录重新扫描时维护冗余关系。

## 5. 总体架构

```text
Compose UI
   |
MusicViewModel
   |---------------------------|
MusicRepository          PlayerController
   |                           |
Room / DocumentFile      MediaController
   |                           |
MediaMetadataRetriever   PlaybackService
   |                           |
FileProvider / Coil          ExoPlayer

DataStore
   ├── 文件夹 URI 集合
   ├── 主题色
   ├── 节拍效果开关
   └── 队列、索引和播放位置
```

普通播放与 FM 各自维护播放快照。模式切换期间暂停自动快照写入，避免异步播放器事件将 FM 状态写入普通快照。

### 5.1 UI 层

- `MainActivity` 启用 edge-to-edge 并承载 Compose。
- `ModalNavigationDrawer` 提供边缘滑动导航。
- `AnimatedContent` 在播放、音乐库、收藏、播放列表和设置页面间切换。
- UI 订阅 ViewModel 的 `StateFlow`，不直接访问 Room 或 ExoPlayer。
- 播放事件通过 `openPlayer` SharedFlow 驱动页面自动返回播放器。

### 5.2 数据层

- `MusicRepository` 递归扫描 SAF 目录、解析元数据、缓存封面并同步 Room。
- `SongDao` 提供歌曲、搜索、收藏和批量同步。
- `PlaylistDao` 提供播放列表关系管理。
- `PlaybackPreferences` 统一保存目录、主题、动效和播放恢复数据。

### 5.3 播放层

- `PlaybackService` 持有 ExoPlayer 和 MediaSession。
- `PlayerController` 通过 MediaController 控制服务。
- UI 只依赖 `PlayerState` 和控制方法。
- MediaItem 包含标题、艺术家、专辑和内嵌封面 URI，供通知和锁屏显示。

## 6. 包与文件设计

```text
life.xstar.music.player
├── MainActivity.kt
├── XstarApplication.kt
├── data
│   ├── local
│   │   ├── MusicDatabase.kt
│   │   ├── SongEntity.kt
│   │   ├── SongDao.kt
│   │   ├── PlaylistEntities.kt
│   │   └── PlaylistDao.kt
│   ├── preferences
│   │   └── PlaybackPreferences.kt
│   └── repository
│       └── MusicRepository.kt
└── player
    ├── PlaybackService.kt
    └── PlayerController.kt

res
├── drawable/ic_launcher_foreground.xml
├── mipmap-anydpi*/ic_launcher*.xml
├── values/themes.xml
└── xml/file_paths.xml
```

## 7. 文件夹授权与扫描设计

### 7.1 目录选择

1. UI 使用 `ActivityResultContracts.OpenDocumentTree` 打开系统目录选择器。
2. 用户选择目录后调用 `takePersistableUriPermission` 获取长期只读权限。
3. 将 tree URI 字符串加入 DataStore 的 `music_folders` 集合。
4. 同一路径由集合语义去重。
5. 添加或移除目录后以最新目录集合触发扫描。

### 7.2 递归扫描

```text
DataStore folder URI set
        |
DocumentFile.fromTreeUri
        |
递归 listFiles
        |
MIME/扩展名过滤
        |
MediaMetadataRetriever
        |
SongEntity + 内嵌封面缓存
        |
Room upsert + 失效记录清理
```

支持的扩展名包括 `mp3`、`m4a`、`aac`、`flac`、`ogg`、`opus`、`wav`、`wma`。若 MIME 以 `audio/` 开头，即使扩展名不在白名单中也可以处理。

### 7.3 歌曲稳定 ID

SAF 文档不存在可靠的 MediaStore ID，因此以完整文档 URI 计算 SHA-256，取前 8 字节折叠为 Long 主键：

```text
stableId = SHA-256(documentUri)[0..7] → Long
```

风险：Long 存在理论哈希碰撞，但对单设备本地音乐库概率可忽略。若未来需要绝对唯一，应将 URI 字符串作为主键。

### 7.4 扫描同步规则

- 扫描在 `Dispatchers.IO` 执行。
- 单个文件解析失败时忽略该文件，不中断整个扫描。
- 本轮不存在的歌曲从 Room 删除。
- upsert 前读取旧记录并保留收藏、播放次数和最近播放时间。
- 目录集合为空时清空音乐索引，但不删除用户原始文件。

## 8. 元数据与封面设计

### 8.1 元数据

`MediaMetadataRetriever` 从 URI 读取：

- `METADATA_KEY_TITLE`
- `METADATA_KEY_ARTIST`
- `METADATA_KEY_ALBUM`
- `METADATA_KEY_DURATION`
- `embeddedPicture`

格式由 DocumentFile MIME 或 ContentResolver MIME 提供。标题缺失时使用不含扩展名的文件名。

### 8.2 封面缓存

1. 读取 `embeddedPicture` 字节数组。
2. 保存到 `cacheDir/album_art/{stableId}.img`。
3. 通过 `FileProvider` 转换为 `${applicationId}.files` 的 `content://` URI。
4. URI 写入 `SongEntity.albumArtUri`。
5. Coil 在 Compose 中加载，Media3 将 URI写入 `MediaMetadata.artworkUri`。
6. 缓存不存在时重新扫描即可恢复；没有内嵌封面则显示默认音乐符号。

FileProvider 仅暴露 `cacheDir/album_art/`，不暴露应用其他私有文件。

### 8.3 在线封面补充

应用不会在扫描完成或启动时自动调用在线封面查询。用户打开专辑详情并点击“获取封面”后，ViewModel 才调用候选查询接口。

```text
用户打开无封面的专辑详情
        |
点击“获取封面”
        |
iTunes Search API 查询专辑
        |
展示候选缩略图
        |
用户选择一张
        |
下载、压缩 JPEG、更新专辑歌曲 albumArtUri
```

实现约束：

- 未知艺术家或未知专辑不发起请求。
- 使用 URL 编码查询参数。
- 每次用户点击只查询当前专辑；候选结果仅保留在当前详情流程中。
- 连接超时 8 秒、读取超时 12 秒。
- 使用 `XSTAR/0.1.0 (local-player)` User-Agent。
- 响应非 2xx、JSON 无 MBID、封面无法解码或下载失败均视为未找到。
- 在线封面失败不影响本地扫描、数据库和播放。
- 设置开关 `online_artwork_enabled` 为 false 时，不提供在线查询入口。
- 使用 iTunes `entity=album` 查询，优先 `country=cn`，失败后尝试 `country=us`。
- 将返回的 `artworkUrl100` 中的 `100x100` 替换为 `600x600`，获取更清晰的候选图。
- 候选图片展示为远程缩略图，用户选择后才下载并写入 Room。

## 9. 数据库设计

### 9.1 songs

```text
mediaStoreId      Long，主键；当前实际为 URI 稳定摘要
title             String
artist            String
album             String
durationMs        Long
uri               String，SAF document URI
mimeType          String?
albumArtUri       String?，FileProvider URI
sourceFolderUri   String，所属 tree URI
codec             String?，规范化编码格式
bitrate           Long?，码率，单位 bps
sampleRate        Int?，采样率，单位 Hz
bitDepth          Int?，位深
channelCount      Int?，声道数
audioQuality      String，Standard/High/Lossless/HiRes
fileName          String?，原始文件名
fileSize          Long?，文件大小，单位 byte
dateAdded         Long，文档 lastModified
isFavorite        Boolean
playCount         Int
lastPlayedAt      Long?
```

字段名 `mediaStoreId` 是早期设计遗留，当前语义为歌曲稳定 ID，后续数据库大版本可重命名为 `songId`。

### 9.2 playlists

```text
id          Long，主键
name        String
createdAt   Long
updatedAt   Long
```

### 9.3 playlist_songs

```text
playlistId  Long，联合主键和外键
songId      Long，联合主键和外键
position    Int
addedAt     Long
```

同一歌曲在同一列表仅允许出现一次，外键删除使用 cascade。

### 9.4 数据库迁移

| 迁移 | 内容 |
| --- | --- |
| 1→2 | `songs` 增加可空 `mimeType` |
| 2→3 | `songs` 增加非空 `sourceFolderUri`，默认空字符串 |
| 3→4 | `songs` 增加音频编码、码率、采样率、位深、声道、音质和文件信息 |

禁止使用 destructive migration 覆盖用户收藏和播放列表。

## 10. DataStore 设计

| Key | 类型 | 用途 |
| --- | --- | --- |
| `queue_ids` | String | 逗号分隔的歌曲 ID 队列 |
| `queue_index` | Long | 当前队列索引 |
| `position_ms` | Long | 当前播放位置 |
| `music_folders` | StringSet | 已授权目录 URI 集合 |
| `theme_accent` | String | `ThemeAccent` 枚举名 |
| `beat_effect` | Boolean | 节拍动态效果开关，默认 true |
| `online_artwork_enabled` | Boolean | 是否允许用户主动获取在线封面，默认 true |
| `fm_mode_enabled` | Boolean | 是否启用本地 FM 模式，默认 false |
| `playback_mode` | String | 普通模式播放规则 |
| `was_playing` | Boolean | 进入 FM 前的普通播放状态 |
| `fm_queue_ids` | String | FM 随机队列歌曲 ID |
| `fm_queue_index` | Long | FM 当前索引 |
| `fm_position_ms` | Long | FM 当前进度 |
| `fm_was_playing` | Boolean | FM 最近播放状态 |

在线封面只在用户主动操作后查询；播放功能不依赖该任务。

播放状态保存使用 1 秒 debounce，避免每 500ms 进度更新都写入磁盘。恢复时按保存 ID 顺序重建队列，并过滤已失效歌曲。

## 11. 播放器设计

### 11.1 PlayerController 能力

```text
playQueue(songs, startIndex)
restoreQueue(songs, index, position)
toggle()
next()
previous()
seek(position)
remove(index)
clear()
setPlaybackMode(mode)
observe state
```

### 11.2 PlayerState

```text
song       SongEntity?
playing    Boolean
position   Long
duration   Long
queue      List<SongEntity>
index      Int
mode       Sequential | Shuffle | RepeatOne
error      String?
```

### 11.3 模式映射

| 应用模式 | Media3 配置 | 行为 |
| --- | --- | --- |
| Sequential | shuffle=false, repeat=OFF | 队列末尾停止 |
| Shuffle | shuffle=true, repeat=ALL | 随机并循环队列 |
| RepeatOne | shuffle=false, repeat=ONE | 重复当前歌曲 |

### 11.4 错误恢复

- `onPlayerError` 将错误映射为简洁 UI 文案。
- 如果存在下一项，自动跳到下一首。
- 删除队列项后同步修正当前索引和当前歌曲。
- listener 只附加一次，释放 Controller 时移除。

## 12. UI 与交互设计

### 12.1 全屏播放页

- Activity 使用 `enableEdgeToEdge()`。
- Manifest 使用 `Theme.Material.NoActionBar` 和 `windowNoTitle=true`。
- 页面无顶部标题，仅在左上角显示菜单按钮。
- 封面容器使用明确相同的 width 和 height，并应用 `CircleShape`。
- 封面最大 330dp，小屏按 `maxWidth - 48dp` 缩小，保证严格 1:1。
- 歌曲摘要显示为“歌手 - 歌曲名称”，名称前显示详情图标，名称右侧显示 `QualityBadge`。
- `QualityBadge` 支持普通、高清、无损和 Hi-Res，使用音质枚举对应颜色和图标。
- 播放页主区域不展示完整音频参数，点击详情图标打开 `SongDetailsSheet`。
- 详情面板展示歌手、专辑、歌曲名称、格式、编码、音质、采样率、位深、码率、声道、时长和文件名。
- 封面获取/更换入口放在 `SongDetailsSheet`，不占用主播放信息区域。
- 进度条下方显示当前时间和总时长。
- 模式、上一首、播放暂停、下一首和队列按钮放在统一高度 76dp 的 Row 中。

### 12.2 节拍动态效果

- 每个主题色对应四个深色氛围颜色。
- 播放时每 380–900ms 随机选择目标颜色。
- `animateColorAsState` 用 650ms tween 平滑过渡背景。
- 封面在 1.0 与约 1.012–1.035 之间轻微缩放。
- 暂停或关闭设置后，LaunchedEffect 退出循环，颜色回归主题色，缩放回归 1.0。
- 当前实现是播放状态驱动的随机节拍，不分析真实音频 PCM 或频谱。

### 12.2 音频质量识别

- `AudioAnalyzer` 使用 `MediaExtractor` 定位音频轨道，读取 MIME、码率、采样率、声道和 PCM 编码。
- `AudioQualityClassifier` 按以下顺序分类：`HiRes`、`Lossless`、`High`、`Standard`。
- 位深 >= 24 或采样率 > 48kHz 为 Hi-Res。
- FLAC、ALAC、APE、WAV、PCM 等无损编码为 Lossless。
- 有损音频码率 >= 256kbps 为 High，其余为 Standard。
- 扫描时保存音频参数；旧数据库通过 3→4 迁移使用 Standard 默认值，重新扫描后更新。

### 12.3 导航

- 应用初始 `Screen.Player`。
- 左边缘右滑打开 `ModalNavigationDrawer`。
- 抽屉入口：正在播放、音乐库、收藏、播放列表、设置。
- 点击任意歌曲后 ViewModel 播放并向 `openPlayer` SharedFlow 发事件，UI 切到播放页并关闭抽屉。
- 不使用底部导航、页面标题和 MiniPlayer。

### 12.4 主题色

支持：

```text
Blue
Violet
Rose
Emerald
Amber
```

抽屉底部使用圆形色块选择。选择后立即更新 Material 3 primary、页面背景渐变和播放动效调色板，并写入 DataStore。

### 12.5 设置页

- 节拍动态效果 Switch。
- 已选音乐文件夹列表。
- 添加文件夹。
- 移除文件夹。
- 重新扫描。
- 应用版本信息。

### 12.6 FM 模式

- 侧滑导航菜单中开启 FM 时，先原子保存普通队列、索引、进度、播放模式和播放状态。
- 从整个音乐库打乱生成 FM 队列，使用循环播放但不向界面暴露队列。
- FM 播放页采用全屏黑胶画布，整个页面单击播放暂停、左右滑动随机换歌、长按打开详情。
- 黑胶视觉由独立旋转层构成，包含唱片沟槽、内圈封面标签、边缘高光、柔和阴影和中心轴；点击不显示 Material 涟漪蒙版。
- 播放进度在不旋转的唱片外缘绘制为闭合圆环；暂停时中心显示半透明暂停标识，播放后消失。
- 标题上方常驻可滚动歌词区域，根据播放进度高亮和自动定位当前行。
- FM 切歌后在本地封面或歌词缺失时自动查询在线候选并保存第一项；在线请求分别受封面和歌词开关约束。
- 播放模式和队列管理入口隐藏。
- FM 与普通模式使用独立 DataStore 快照，重启时根据 `fm_mode_enabled` 恢复对应现场。
- 关闭 FM 时过滤已失效歌曲并恢复普通现场；普通队列全部失效时恢复为空播放器。
- FM 中从音乐库主动选歌代表离开电台：关闭 FM，但不先恢复旧队列，直接播放用户选择的来源列表。

### 12.7 DLNA 投屏

- 入口位于侧滑导航菜单“投屏”卡片，FM 模式下禁用并提示。
- 设备发现使用 SSDP M-SEARCH/NOTIFY，MulticastLock 保证组播接收，过滤 `MediaRenderer:1` 且含 `AVTransport:1` 控制服务的设备。
- 设备描述解析友好名称、型号与控制 URL；控制 URL 相对地址基于设备描述来源地址解析。
- 音频投流由 `LocalMusicServer` 提供：绑定本机随机端口，URL 带随机 token 并校验 songId 白名单；响应支持 GET/HEAD 与 Range/206 用于渲染器 seek；数据通过 `ContentResolver.openInputStream` 读取 SAF 授权文件。
- `RendererClient` 封装 SOAP `AVTransport:1` 的 SetAVTransportURI、Play、Pause、Stop、Seek、GetTransportInfo、GetPositionInfo，接口化以便替换实现。
- `CastSessionController` 维护会话状态机：Idle→Connecting→Connected(Playing/Paused)→Disconnected；2.5 秒轮询同步进度与播放态，曲目结束按顺序/随机/单曲自动继续或结束。
- 投屏激活时暂停本地 ExoPlayer 并保留现场，断开后恢复；激活期间音乐库等播放入口改写为投屏队列。
- `CastSessionService` 以 `mediaPlayback` 前台服务保活，通知可一键断开。
- 明文局域网 http 通过 `res/xml/network_security_config.xml` 允许；新增 `CHANGE_WIFI_MULTICAST_STATE` 权限。

## 13. 应用图标设计

- Manifest 同时配置 `android:icon` 和 `android:roundIcon`。
- Android 8+ 使用 adaptive icon。
- 深色 `#111722` 背景。
- 前景仅一个居中的小八分音符，使用主题蓝色。
- 同时提供普通矢量 fallback 和圆形 fallback。
- 前景图形位于安全区域内，避免 Launcher 蒙版裁切或拉伸变形。

## 14. 权限与 Manifest

- SAF 目录读取依赖用户选择和持久化 URI 权限，不需要为扫描申请全存储访问。
- MediaSessionService 声明 `mediaPlayback` 前台服务类型。
- 声明前台服务和媒体播放前台服务权限。
- Android 13+ 通知按系统要求处理。
- FileProvider `exported=false` 且 `grantUriPermissions=true`。
- FileProvider 路径仅允许 `cache/album_art`。

旧版媒体读取权限若 Manifest 中仍保留，应在后续清理确认 SAF 扫描不再依赖后移除，减少权限申请面。

## 15. 歌词设计

### 15.1 来源优先级

```text
filesDir/lyrics/{songId}.lrc
        ↓ 没有
音频同目录同名 .lrc
        ↓ 没有
播放页显示“获取歌词”
        ↓ 用户主动点击
LRCLIB /api/get 精确查询
        ↓ 失败
LRCLIB /api/search 候选查询
        ↓ 用户选择
保存歌词并显示滚动视图
```

当前 Android SDK 没有公开通用的 `MediaMetadataRetriever.METADATA_KEY_LYRICS` 常量，因此第一版不依赖该 API 读取内嵌歌词。`LyricsSource.Embedded` 模型保留用于后续接入专用标签解析库；当前实际可用来源为本地 LRC、缓存歌词和 LRCLIB。歌词层已预留内嵌歌词来源，不应影响本地 LRC 和在线歌词功能。

### 15.2 LRC 解析

`LrcParser` 支持 `[mm:ss.xx]`、`[mm:ss:xxx]`、一行多个时间标签、UTF-8 BOM、元数据标签过滤和时间排序。解析结果为 `LyricLine(timeMs, text)`；纯文本歌词使用 `timeMs = null`。

### 15.3 播放页交互

- 点击旋转 CD 切换到歌词视图。
- 切换歌曲时重置为 CD 视图并重新加载歌词。
- 当前行通过播放位置计算。
- 当前行变化时使用 `LazyListState.animateScrollToItem`。
- 无歌词时显示“获取歌词”按钮。
- LRCLIB 多结果显示歌曲名、艺术家和同步/纯文本类型，用户选择后保存。
- 在线歌词保存到 `filesDir/lyrics/{songId}.lrc`。

### 15.4 在线歌词开关

DataStore key：

```text
online_lyrics_enabled: Boolean，默认 true
```

关闭时不发起 LRCLIB 请求，但本地同名 LRC 和已缓存歌词仍可读取。

## 16. 错误处理

| 错误 | 用户行为 | 系统行为 |
| --- | --- | --- |
| 未选择目录 | 展示选择音乐文件夹引导 | 不扫描设备 |
| 目录权限失效 | 目录扫描无结果或提示重新选择 | 跳过不可访问目录 |
| 文件元数据损坏 | 不阻断其他歌曲扫描 | 忽略该文件 |
| 无内嵌封面 | 展示默认音乐符号 | 不视为错误 |
| 缓存封面丢失 | 默认图或重新扫描恢复 | 音频仍可播放 |
| 音频解码失败 | 显示无法播放提示 | 有下一首时自动跳过 |
| URI 已失效 | 提示歌曲不可用 | 重新扫描后清理索引 |
| 服务连接失败 | 控件暂不可用 | Controller 按系统连接流程恢复 |

## 16. 需求追踪

| 需求范围 | 设计落点 |
| --- | --- |
| FR-001～FR-009 | OpenDocumentTree、持久化 URI 权限、DataStore folders、DocumentFile |
| FR-010～FR-017 | MediaMetadataRetriever、stableId、FileProvider、Room upsert |
| FR-018～FR-021 | AlbumArtworkSheet、ArtworkCandidate、iTunes Search API、Room album 更新 |
| FR-030～FR-037 | SongsList、GroupsList、搜索过滤、openPlayer SharedFlow |
| FR-040～FR-052 | PlayerScreen、NoActionBar、CircleShape、播放进度、详情面板和 QualityBadge |
| FR-060～FR-068 | PlayerController、PlaybackMode、QueueSheet、错误跳过 |
| FR-070～FR-076 | ModalNavigationDrawer、ThemeAccent、edge-to-edge |
| FR-080～FR-085 | pulseColors、Compose animation、beatEffect DataStore |
| FR-090～FR-094 | SongDao、PlaylistDao、PlaylistSheet |
| FR-100～FR-105 | PlaybackService、MediaSession、PlaybackPreferences |
| FR-110～FR-118 | LyricsRepository、LrcParser、LyricsView、LRCLIB、歌词缓存 |
| FR-120～FR-122 | adaptive icon、普通和圆形 mipmap 资源 |
| FR-130～FR-141 | FmMode、双播放快照、FM 随机队列、FmPlayerScreen、黑胶画布、环形进度、歌词预览、自动补充资源 |
| FR-150～FR-158 | SsdpDiscovery、LocalMusicServer、RendererClient、CastSessionController、CastSessionService、投屏入口与胶囊 |

## 17. 测试设计

### 17.1 数据与扫描

- 添加两个目录并验证递归扫描和去重。
- 重复添加同一目录。
- 移除一个目录并验证索引清理。
- 目录含子目录、非音频文件、损坏音频和无标签音频。
- URI 权限在应用重启后仍有效。
- 各格式元数据和内嵌封面提取。

### 17.2 播放

- 顺序、随机、单曲模式。
- 上一首、下一首、进度拖动和队列删除。
- 队列末尾、删除当前歌曲、清空队列。
- 损坏文件自动跳过。
- Activity 重建、应用重启和后台播放恢复。
- 通知、锁屏、耳机和蓝牙控制。

### 17.3 UI

- 播放页封面在窄屏、宽屏和横屏保持正圆。
- 从歌曲、专辑、艺术家、收藏、队列和播放列表点击后回到播放页。
- 侧滑菜单和菜单按钮均可用。
- 五个播放按钮水平对齐且触控区域足够。
- 五种主题即时生效并持久化。
- 节拍效果关闭后动态颜色和缩放停止，重启后保持关闭。
- 有封面、无封面、超长标题和未知格式显示。

### 17.4 安装包

- 普通 Launcher 和圆形 Launcher 显示单一小音乐符号。
- 图标在不同厂商蒙版下不裁切、不拉伸。
- Debug 和 Release Manifest 均使用 NoActionBar 主题。

### 17.5 FM 模式

- 空音乐库不可开启 FM，单曲音乐库随机切换保持当前歌曲。
- 开启 FM 后立即播放并返回播放页，普通现场不被 FM 进度覆盖。
- 左右滑动均切换到不同随机歌曲，整页单击、长按和歌词纵向滚动不互相误触。
- 黑胶沟槽、标签、高光、阴影和环形进度保持同心，播放与暂停状态显示正确。
- 缺失封面或歌词时自动保存第一候选；关闭对应在线开关后不发起请求。
- FM 中应用重启后继续 FM；随后关闭 FM 仍可恢复进入前的普通现场。
- FM 中从音乐库选歌后退出 FM，并直接播放所选歌曲。
- 扫描移除部分歌曲后，普通与 FM 快照都过滤失效歌曲并修正索引。

## 18. 当前风险与后续优化

### R-001：递归扫描性能

大型目录中 `DocumentFile.listFiles()` 和逐文件 MediaMetadataRetriever 成本较高。后续可增加扫描进度、取消、并发上限和基于 lastModified 的增量扫描。

### R-002：缓存封面容量

缓存目录可能增长。后续应增加基于当前歌曲索引的孤立封面清理和尺寸压缩。

### R-003：随机节拍并非真实节奏

当前动态效果由播放状态和随机时间驱动，视觉上模拟节拍，但不反映真实音频能量。若需要真实节奏，应评估 ExoPlayer audio processor、Visualizer API 或离线节拍分析，同时考虑权限、功耗和兼容性。

### R-004：Windows Gradle 文件锁

构建超时或中断可能遗留 Gradle Daemon 并锁定 `app/build`。开发环境应统一 Gradle 8.9，必要时执行 `gradle --stop` 与 `clean --no-daemon`。

### R-005：遗留命名

`SongEntity.mediaStoreId` 当前已不再表示 MediaStore ID。后续 schema 大版本应迁移为 `songId`，避免语义误导。

### R-006：DLNA 厂商兼容差异

不同渲染器对 SOAP 命名空间、是否要求 DIDL-Lite、`InstanceID`、Seek 单位与响应包结构实现不一致；播放器 seek 依赖渲染器支持 Range。自研协议便于逐条抓包调试，需要按真实设备（坚果 N1 Pro）联调并记录兼容矩阵。投屏播放格式解码取决于渲染器能力，无损格式可能需要在后续加入转码提示或转码流。

### R-007：明文局域网流量

投屏依赖局域网内 HTTP 与 SOAP，`network_security_config.xml` 允许明文。该配置不区分局域网与公网，后续可在投屏链路内对 URL 来源做白名单校验，避免对任意地址发起请求。
