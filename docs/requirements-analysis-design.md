# 本地音乐播放器需求分析与设计文档

## 1. 文档目的

本文档将《XSTAR 本地音乐播放器需求文档》中的 MVP 需求转化为可实施的 Android 技术设计，作为工程初始化、开发拆分、联调和测试的依据。

## 2. 设计基线

### 2.1 技术选型

| 层次 | 方案 |
| --- | --- |
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 架构 | 单 Activity、分层架构、单向数据流 |
| 异步 | Kotlin Coroutines + Flow/StateFlow |
| 播放 | AndroidX Media3 ExoPlayer |
| 后台媒体 | MediaSessionService + MediaSession |
| 数据库 | Room |
| 设置 | DataStore Preferences |
| 图片 | Coil |
| 导航 | Navigation Compose |
| 注入 | MVP 可手动组装，后续按需要引入 Hilt |

### 2.1 应用工程标识

| 项目 | 值 |
| --- | --- |
| 应用名称 | XSTAR |
| `applicationId` | `life.xstar.music.player` |
| `namespace` | `life.xstar.music.player` |
| 首版目标 | V0.1 可播放版本 |
| 建议最低版本 | Android 8.0 |
| 网络依赖 | 无 |
| 账号体系 | 无 |

### 2.2 版本策略

- 以当前稳定 Android SDK 和 AndroidX 版本为准。
- 建议最低支持 Android 8.0，最终 minSdk 在工程初始化时根据设备覆盖率确认。
- Android 13 及以上申请 `READ_MEDIA_AUDIO`。
- Android 12 及以下申请 `READ_EXTERNAL_STORAGE`，并按系统版本处理权限差异。
- Android 13 及以上如需要显示媒体通知，评估并申请 `POST_NOTIFICATIONS`。

## 3. 需求分析

### 3.1 核心参与者

- 用户：浏览、搜索、管理和播放本地音乐。
- Android MediaStore：提供设备媒体索引。
- Android 系统媒体框架：提供音频焦点、通知、锁屏和外部控制。
- Media3 播放器：解码并播放音频。

应用不包含服务端参与者，MVP 的业务闭环完全在设备本地完成。

### 3.2 领域对象

```text
Song       本地音频的应用索引和用户播放统计
Album      由歌曲的专辑字段聚合得到的只读展示对象
Artist     由歌曲的艺术家字段聚合得到的只读展示对象
Playlist   用户创建的持久化播放列表
Queue      当前播放上下文中的临时歌曲序列
Playback   当前播放器状态
Setting    用户偏好设置
```

其中 `Album` 和 `Artist` 不强制单独建表，优先通过 `songs` 聚合查询，减少扫描同步和数据一致性成本。

### 3.3 关键用例

#### UC-01 首次建立音乐库

1. 用户启动应用。
2. 应用检查媒体读取权限。
3. 未授权时展示权限说明并请求权限。
4. 授权成功后启动后台扫描任务。
5. 扫描任务读取 MediaStore，转换为 `Song`，执行数据库 upsert。
6. 扫描结束后标记失效记录并发布音乐库状态。
7. UI 展示歌曲列表或无音乐状态。

失败处理：权限拒绝、查询异常或数据库异常都必须转为可展示的错误状态，并允许用户重试。

#### UC-02 播放歌曲

1. 用户在歌曲、专辑、艺术家或搜索结果中点击歌曲。
2. ViewModel 请求 Repository 构造播放上下文。
3. PlayerController 将歌曲转换为 Media3 `MediaItem` 并发送给 `MediaController`。
4. `PlaybackService` 设置队列并调用播放器播放。
5. 服务发布播放状态，UI 更新迷你播放器和完整播放器页。

#### UC-03 后台播放

1. UI 通过 `MediaController` 连接 `PlaybackService`。
2. 应用进入后台后服务继续持有播放器和媒体会话。
3. 系统通知、锁屏、蓝牙设备等通过 MediaSession 发送控制命令。
4. 播放器状态变化经服务回传给 UI。

#### UC-04 管理播放列表

1. 用户创建播放列表。
2. 应用写入 `playlists`。
3. 用户添加歌曲，写入 `playlist_songs` 并分配 position。
4. 用户调整顺序时在事务中更新 position。
5. 用户点击播放时读取列表快照并生成临时 Queue。

## 4. 总体架构

```text
Compose UI
    |
ViewModel / UiState
    |
Use Case / Repository
    |                    \
Room Database       PlayerController
    |                    |
MediaStore Scanner  MediaController
                         |
                 PlaybackService
                         |
                      ExoPlayer
```

### 4.1 分层职责

#### UI 层

- 负责渲染页面和处理用户输入。
- 只订阅 ViewModel 暴露的不可变 UI 状态。
- 不直接访问 Room、MediaStore 或 ExoPlayer。

#### ViewModel 层

- 编排页面用例。
- 将 Repository 和 PlayerController 的状态合并成页面状态。
- 处理一次性事件，例如 Toast、Snackbar 和导航。

#### Domain 层

- 定义领域模型和用例接口。
- 包含播放列表排序、默认标签、播放结束行为等业务规则。

#### Data 层

- 负责 Room、MediaStore 和 DataStore 的访问。
- 将外部数据模型转换为领域模型。
- 处理扫描 upsert、失效清理和数据库事务。

#### Player 层

- 负责 Media3 播放器、媒体会话、播放队列和音频焦点。
- 对外提供统一的播放控制接口和只读播放状态。

## 5. 模块与包设计

MVP 使用单模块工程，但按职责拆分包，后续可以平滑拆成多模块：

```text
life.xstar.music.player
├── app
│   ├── App.kt
│   └── MainActivity.kt
├── data
│   ├── local
│   │   ├── MusicDatabase.kt
│   │   ├── SongDao.kt
│   │   ├── PlaylistDao.kt
│   │   └── entities
│   ├── mediastore
│   │   └── MediaStoreScanner.kt
│   ├── preferences
│   │   └── SettingsDataStore.kt
│   └── repository
├── domain
│   ├── model
│   └── usecase
├── player
│   ├── PlaybackService.kt
│   ├── PlayerController.kt
│   └── PlaybackState.kt
├── ui
│   ├── home
│   ├── library
│   ├── search
│   ├── playlist
│   ├── nowplaying
│   ├── settings
│   └── components
└── navigation
```

工程初始化时应将上述包名前缀直接用于 Kotlin 源码目录、Manifest、Gradle `namespace` 和 `applicationId`，避免后续重命名造成资源、测试和媒体服务配置遗漏。

## 5.1 从空工程开始的实施路线

实施遵循“先打通播放闭环，再扩展管理能力”的原则。第一条必须验证的主链路是：

```text
申请权限 → 读取 MediaStore → 写入 Room → 展示歌曲 →
点击歌曲 → Media3 播放 → 后台播放 → 通知栏和锁屏控制
```

### 阶段 0：产品与工程决策

- 确认应用名称 XSTAR、包名 `life.xstar.music.player` 和品牌资源。
- 确认最低 Android 版本、目标 SDK 和测试设备矩阵。
- 确认首版只读取 MediaStore，不做文件夹浏览和原文件编辑。
- 确认首版仅实现本地音频播放，不引入网络服务。

完成标准：工程参数冻结，后续代码使用统一包名和版本目标。

### 阶段 1：Android 工程骨架

- 创建 Kotlin + Compose 单模块 Android 工程。
- 配置 Compose、Material 3、Navigation Compose、Room、Media3 和 DataStore。
- 创建 `MainActivity`、主题、导航和页面占位。
- 建立 `app`、`data`、`domain`、`player`、`ui`、`navigation` 包结构。
- 先用假数据验证歌曲列表、迷你播放器和完整播放页的布局。

交付物：可编译、可安装、可导航的 XSTAR 空壳应用。

### 阶段 2：媒体权限

- 集中实现权限检查和请求，不在页面中散落 Android 版本判断。
- Android 13 及以上使用 `READ_MEDIA_AUDIO`，Android 12 及以下使用 `READ_EXTERNAL_STORAGE`。
- 按目标 SDK 处理 `POST_NOTIFICATIONS`，确保媒体通知有明确状态。
- 实现未请求、已授权、拒绝和永久拒绝状态页。

完成标准：未授权时不读取 MediaStore；授权后能够触发扫描；永久拒绝时可以跳转系统设置。

### 阶段 3：MediaStore 与 Room 音乐库

- 定义 `SongEntity`、`SongDao` 和 `MusicDatabase`。
- 实现 `MediaStoreScanner`，读取歌曲元数据并填充默认标签。
- 在 `Dispatchers.IO` 执行分批查询和事务 upsert。
- 记录本次扫描的媒体 ID，扫描成功后清理失效记录。
- 用 `ScanState` 向 UI 发布空、扫描中、成功和失败状态。
- 先完成歌曲列表，再实现专辑和艺术家聚合查询。

完成标准：真实设备授权后可以显示音乐；重新扫描不产生重复记录；被删除的文件不会继续显示。

### 阶段 4：Media3 播放核心

- 实现 `PlaybackService : MediaSessionService`。
- 初始化 ExoPlayer、MediaSession 和播放通知。
- 实现 `PlayerController`，禁止 UI 直接依赖 ExoPlayer。
- 完成播放、暂停、上一首、下一首、进度拖动和基础队列。
- 完成顺序、单曲循环、列表循环和随机播放。
- 将播放器事件转换为 `PlaybackUiState`。

完成标准：点击歌曲能够稳定播放；单个损坏文件不会导致应用崩溃；队列切换和进度状态能够同步到 UI。

### 阶段 5：后台和系统控制

- 完成 MediaSessionService 的 Manifest 与前台媒体服务配置。
- 验证应用退后台、锁屏、通知栏、蓝牙和耳机控制。
- 处理音频焦点、来电打断和耳机拔出暂停。
- 处理 Activity 重建和 MediaController 重连。

完成标准：应用不在前台时仍能播放和控制；系统媒体入口状态正确；音频中断行为符合预期。

### 阶段 6：状态恢复

- 使用 DataStore 保存最近歌曲、播放位置、队列、队列索引和播放模式。
- 在歌曲切换、暂停、进入后台等时机保存状态，避免高频写入。
- 启动或服务重连时恢复状态，并过滤失效歌曲和不可访问 URI。

完成标准：应用重启后可以恢复最近播放上下文，失效文件不会阻塞恢复流程。

### 阶段 7：搜索与音乐分类

- 实现歌曲、专辑、艺术家聚合列表。
- 实现标题、艺术家、专辑和专辑艺术家的本地搜索。
- 搜索结果支持播放、加入队列和加入播放列表。
- 音乐库增大后再引入 Paging 3，避免提前增加复杂度。

完成标准：搜索结果准确、可操作；数千首歌曲下列表和搜索没有明显卡顿。

### 阶段 8：收藏与自定义播放列表

- 创建 `playlists` 和 `playlist_songs` 表及 DAO。
- 实现创建、重命名、删除、添加、移除和排序。
- 播放列表播放时生成临时 Queue，不修改持久化列表。
- 删除播放列表前二次确认，避免误操作。

完成标准：播放列表数据重启后保留；歌曲顺序稳定；队列操作不会意外修改播放列表。

### 阶段 9：设置、质量与发布准备

- 实现重新扫描、主题、默认排序和播放设置。
- 完善加载、空数据、无权限、扫描失败、播放失败和无搜索结果状态。
- 优化封面缓存、列表分页和进度状态更新。
- 完成无障碍、小屏、平板和横屏回归。
- 完成 Release 构建、隐私说明和真机测试矩阵。

完成标准：达到需求文档中的 MVP 验收标准，且目标设备无明显崩溃、卡顿和布局溢出。

## 5.2 版本交付路线

### V0.1：可播放版本

包含权限、MediaStore 扫描、歌曲列表、Media3 播放、基础播放控制、后台播放、通知栏控制和简单播放页。该版本优先验证真实设备上的格式兼容性、锁屏稳定性和播放错误恢复。

### V0.2：音乐库版本

增加搜索、专辑、艺术家、最近播放、最近添加、收藏和播放状态恢复。

### V0.3：管理版本

增加自定义播放列表、队列编辑、扫描配置、设置页、平板适配和性能优化。

### V1.0：发布版本

完成兼容性回归、异常处理、无障碍检查、隐私说明、应用图标、启动页、Release 签名和版本管理。

## 5.3 推荐代码实现顺序

```text
1. Android 工程和 Gradle 配置
2. Compose 主题、导航和页面状态约定
3. 权限封装
4. Song 领域模型与 Room 数据库
5. MediaStoreScanner 和歌曲列表
6. PlaybackService
7. PlayerController
8. 播放控制、迷你播放器和完整播放页
9. 后台播放、通知、音频焦点和设备事件
10. 播放状态持久化与恢复
11. 搜索、专辑和艺术家
12. 收藏和播放列表
13. 设置、性能优化和真机回归
```

每个阶段都应保留一个可运行版本，避免在权限、数据、播放器和 UI 问题未验证时继续叠加功能。

## 6. 数据设计

### 6.1 songs 表

```text
id              Long，应用主键
mediaStoreId    Long，MediaStore 稳定标识
title           String
artist          String
album           String
albumArtist     String?
durationMs      Long
uri             String
mimeType        String?
year            Int?
trackNumber     Int?
discNumber      Int?
albumArtUri     String?
dateAdded       Long
dateModified    Long?
isFavorite      Boolean
playCount       Int
lastPlayedAt    Long?
```

`mediaStoreId` 建立唯一索引。`uri` 和 `dateModified` 用于检测媒体变化；播放前仍需验证 URI 是否可访问。

### 6.2 playlists 表

```text
id              Long，应用主键
name            String
createdAt       Long
updatedAt       Long
```

### 6.3 playlist_songs 表

```text
playlistId      Long，外键
songId          Long，外键
position        Int
addedAt         Long
```

主键建议为 `playlistId + songId`，如果产品未来允许同一歌曲在同一播放列表重复出现，再改为独立项 ID。

### 6.4 播放状态持久化

以下数据写入 DataStore：

- 最近歌曲 ID。
- 最近播放位置。
- 最近播放队列歌曲 ID 列表。
- 当前队列索引。
- repeatMode 和 shuffleEnabled。
- 用户播放和扫描设置。

持久化队列恢复时必须过滤数据库中已不存在或 URI 已失效的歌曲。

## 7. 播放器设计

### 7.1 服务生命周期

- `PlaybackService` 继承 `MediaSessionService`。
- 服务创建时初始化 ExoPlayer、MediaSession 和通知配置。
- `onGetSession` 返回当前 MediaSession。
- 没有播放任务且队列为空时允许服务按系统规则结束。
- 服务销毁时释放播放器、媒体会话和监听器。

### 7.2 PlayerController 接口

建议提供以下能力：

```text
connect()
play(songId)
playQueue(songIds, startIndex)
pause()
resume()
skipNext()
skipPrevious()
seekTo(positionMs)
setRepeatMode(mode)
setShuffleEnabled(enabled)
removeFromQueue(index)
clearQueue()
observePlaybackState()
```

Controller 不将 ExoPlayer 类型暴露给 UI，避免界面层和具体播放实现耦合。

### 7.3 播放状态

```text
PlaybackUiState
- currentSong: Song?
- isPlaying: Boolean
- playbackState: Idle | Buffering | Ready | Ended | Error
- positionMs: Long
- durationMs: Long
- queue: List<Song>
- currentIndex: Int
- repeatMode: Off | One | All
- shuffleEnabled: Boolean
- errorMessage: String?
```

进度更新应采用定时采样或播放器事件驱动的轻量 Flow，避免高频更新造成不必要的 Compose 重组。

### 7.4 音频焦点与中断

- 永久丢失音频焦点时暂停。
- 短暂丢失焦点时暂停或降低音量，行为遵循 Media3 默认策略并通过测试确认。
- 获得焦点后是否自动恢复由播放器和用户设置决定，默认避免在用户主动暂停后自动播放。
- 耳机拔出通过音频设备事件暂停。

## 8. MediaStore 扫描设计

### 8.1 查询与转换

查询 `MediaStore.Audio.Media`，至少过滤：

- `IS_MUSIC != 0`，或按产品策略包含用户明确选择的音频类型。
- `DURATION > 0`。
- 当前应用可访问的 URI。

扫描器负责：

1. 获取当前媒体 ID 集合。
2. 分批读取 Cursor，转换为数据库实体。
3. 使用事务批量 upsert。
4. 删除本次扫描未发现的旧记录。
5. 通过 Flow 发布扫描进度、成功和失败状态。

### 8.2 扫描并发规则

- 同一时间只允许一个扫描任务。
- 重复触发时取消或合并后续请求。
- 扫描可取消，取消后不应执行基于不完整集合的全量删除。
- 扫描任务在 `Dispatchers.IO` 执行。

## 9. UI 与交互设计

### 9.1 页面状态模型

每个页面至少区分以下状态：

```text
Loading
Content
Empty
PermissionRequired
Error
```

页面不得通过列表为空来推断所有异常情况；无权限、无音乐、搜索无结果和扫描失败需要不同的引导文案与操作。

### 9.2 组件

- `MiniPlayer`：全局底部迷你播放器。
- `SongListItem`：歌曲信息、封面、收藏和更多操作。
- `AlbumGridItem`：专辑封面和专辑信息。
- `PlaybackControls`：进度、播放、切歌、循环和随机控制。
- `QueueSheet`：当前播放队列管理。
- `PermissionStateView`：权限说明和授权按钮。
- `ScanStatusView`：扫描进度、重试和完成提示。

### 9.3 导航

建议路由：

```text
home
library/songs
library/albums
library/artists
library/playlists
playlist/{playlistId}
search
now-playing
settings
```

播放器状态由应用级共享状态提供，不通过导航参数传递完整歌曲对象。

## 10. 权限与 Manifest 要求

根据 minSdk 和目标 SDK 配置媒体权限。服务至少需要：

- 前台媒体播放服务声明。
- 媒体播放服务类型配置。
- MediaSession 相关服务配置。
- 通知渠道和必要的通知权限处理。

具体 Manifest 属性应以目标 Android SDK 和 Media3 当前 API 为准，在工程初始化时通过编译和真机测试确认，避免复制已废弃的旧版配置。

## 11. 错误处理

| 错误 | 用户可见行为 | 系统行为 |
| --- | --- | --- |
| 未授予媒体权限 | 展示授权说明和重试按钮 | 不启动扫描 |
| 没有本地音乐 | 展示空状态和重新扫描按钮 | 保持正常可用 |
| MediaStore 查询失败 | 展示失败原因和重试 | 记录诊断日志 |
| URI 不可访问 | 提示歌曲不可用 | 从当前队列跳过 |
| 音频解码失败 | 提示播放失败 | 跳过当前歌曲，继续队列 |
| 数据库写入失败 | 展示操作失败提示 | 保留已有数据，不覆盖有效记录 |
| 服务连接失败 | 播放控件显示不可用 | 尝试重新连接服务 |

错误消息面向用户应简洁，详细异常仅进入本地诊断日志，不上传设备。

## 12. 需求到设计的追踪关系

| 需求范围 | 主要设计落点 |
| --- | --- |
| FR-001 至 FR-005 | Permission manager、启动状态机、MediaStoreScanner |
| FR-010 至 FR-018 | MediaStoreScanner、SongDao、Room 事务、DataStore 设置 |
| FR-020 至 FR-026 | SongDao 聚合查询、Library ViewModel、Compose 列表 |
| FR-030 至 FR-033 | 本地数据库搜索查询、Search ViewModel |
| FR-040 至 FR-047 | PlayerController、PlaybackService、PlaybackState、DataStore |
| FR-050 至 FR-055 | MediaSessionService、通知、音频焦点和设备事件 |
| FR-060 至 FR-065 | PlaylistDao、事务用例、Playlist ViewModel |
| FR-070 至 FR-073 | SettingsDataStore、Settings ViewModel、后续扩展接口 |

## 13. 测试设计

### 13.1 单元测试

- 默认标签填充和排序规则。
- 循环模式、随机队列和队列边界行为。
- 播放列表 position 重排。
- 搜索条件组合和空关键字行为。
- 播放状态映射。
- 扫描结果的新增、更新和删除判定。

### 13.2 数据层测试

- Room DAO 查询、分页和事务。
- 媒体实体 upsert 不产生重复歌曲。
- 删除媒体后失效索引被清理。
- DataStore 写入和恢复。

### 13.3 集成测试

- 权限授予、拒绝和重新授权。
- MediaStore 到 Room 的完整扫描流程。
- Controller 到 PlaybackService 的播放控制链路。
- 播放队列切换和状态回传。

### 13.4 真机验收

至少覆盖：

- Android 8、Android 12、Android 13 及以上。
- MP3、M4A、FLAC、OGG、WAV 等实际文件。
- 无标签、中文标签、超长标题和损坏文件。
- 锁屏、通知栏、耳机拔出、蓝牙控制和来电打断。
- 无音乐、无权限、存储变化和大型音乐库。

## 14. 开发拆分与交付顺序

### Iteration 1：工程骨架

- 初始化 Kotlin、Compose、Material 3 和 Navigation。
- 建立包结构、主题、基础页面和全局 UI 状态约定。

### Iteration 2：媒体库

- 实现权限管理。
- 实现 MediaStoreScanner、Room 数据库和歌曲列表。
- 完成无权限、无音乐和扫描失败状态。

### Iteration 3：播放核心

- 实现 PlaybackService、MediaSession 和 PlayerController。
- 完成歌曲播放、暂停、切歌、进度和迷你播放器。

### Iteration 4：系统集成

- 完成后台播放、通知、锁屏、耳机、蓝牙和音频焦点。
- 完成播放上下文恢复。

### Iteration 5：管理能力

- 完成收藏、播放列表、队列操作和搜索。

### Iteration 6：质量与发布准备

- 完成性能优化、异常场景、深色模式、无障碍和真机回归。

## 15. 技术风险与决策记录

### R-001：媒体权限差异

风险：不同 Android 版本权限名称和通知行为不同。

决策：权限逻辑集中封装，禁止页面散落版本判断；以真机矩阵验证。

### R-002：媒体标签不一致

风险：歌曲可能缺少标题、专辑或艺术家，聚合结果不稳定。

决策：统一默认值和规范化策略；MVP 不修改原文件标签。

### R-003：大型音乐库性能

风险：一次性加载所有歌曲或封面会造成卡顿和内存压力。

决策：数据库排序和分页、后台扫描、Coil 缓存；必要时再引入 Paging 3。

### R-004：队列与持久化播放列表混淆

风险：用户从专辑或搜索结果播放时意外修改播放列表。

决策：Queue 始终是临时播放上下文，Playlist 始终是显式持久化数据。

### R-005：文件生命周期变化

风险：外部文件被移动、删除或权限失效。

决策：保存 URI 和媒体修改信息；播放前验证，扫描时清理，播放失败时跳过。

## 16. 待确认事项

以下事项不阻塞 MVP 架构，但应在工程初始化前确认：

- 应用正式名称、包名和品牌视觉。
- 最低 Android 版本和目标设备覆盖范围。
- 是否将短音频、播客或录音纳入音乐库。
- 是否在 MVP 加入 Paging 3。
- 是否需要支持平板横屏双栏布局。
- MVP 是否必须包含 Android Auto。
- 是否需要导出播放列表或备份本地配置。
