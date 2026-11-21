package life.xstar.music.player.ui.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import life.xstar.music.player.MusicViewModel
import life.xstar.music.player.PageTopBar
import life.xstar.music.player.PageSearchField
import life.xstar.music.player.data.local.PlaylistEntity
import life.xstar.music.player.data.local.SongEntity
import life.xstar.music.player.data.preferences.PlaylistStyle
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistLibraryScreen(vm: MusicViewModel, openMenu: () -> Unit) {
    val lists by vm.playlists.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val style by vm.playlistStyle.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var focusId by rememberSaveable { mutableStateOf<Long?>(null) }
    var nameDialog by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val selected=lists.firstOrNull { it.id==selectedId }
    val songFlow=remember(selectedId) { selectedId?.let(vm::playlistSongs) ?: flowOf(emptyList()) }
    val links by songFlow.collectAsStateWithLifecycle(emptyList())
    val songMap=remember(songs) { songs.associateBy { it.mediaStoreId } }
    val current=remember(links,songMap) { links.mapNotNull { songMap[it.songId] } }
    val shown=remember(lists,query) { lists.filter { it.name.contains(query.trim(),ignoreCase=true) } }
    val focused=shown.firstOrNull { it.id==focusId } ?: shown.firstOrNull()
    fun open(item: PlaylistEntity) { selectedId=item.id; managing=false }
    fun cycle() { if(shown.isNotEmpty()) focusId=shown[(shown.indexOfFirst { it.id==focused?.id }+1)%shown.size].id }
    BackHandler(selectedId!=null) { selectedId=null;managing=false }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            PageTopBar(selected?.name ?: "播放列表", { if(selectedId!=null) { selectedId=null; managing=false } else openMenu() }, back=selectedId!=null) {
                if(selected!=null) Box {
                    IconButton({menu=true}) { Icon(Icons.Default.MoreHoriz,"歌单操作") }
                    DropdownMenu(menu,{menu=false}) {
                        DropdownMenuItem(text={Text("重命名")},onClick={menu=false;rename=true;name=selected.name;nameDialog=true},leadingIcon={Icon(Icons.Default.Edit,null)})
                        DropdownMenuItem(text={Text("删除歌单")},onClick={menu=false;deleting=true},leadingIcon={Icon(Icons.Default.DeleteOutline,null)})
                    }
                } else {
                    IconButton({searching=!searching;if(!searching)query=""}) { Icon(Icons.Default.Search,"搜索歌单") }
                    IconButton({rename=false;name="";nameDialog=true}) { Icon(Icons.Default.Add,"新建歌单") }
                }
            }
            if(selectedId==null) {
                if(searching) PageSearchField(query, {query=it}, "搜索播放列表")
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(start=22.dp,end=22.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${lists.size} 份播放列表 · ${style.title}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(style.subtitle,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=9.dp))
                    }
                    if(focused!=null&&!searching) item {
                        LibraryFeature(style,shown,focused,playback.playing,onOpen={open(it)},onCycle={cycle()},onFocus={focusId=it.id})
                    }
                    item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(if(style==PlaylistStyle.Bookshelf)"滑过书架，抽一本声音" else "我的收藏",fontSize=12.sp)
                        Text("${shown.size} 份歌单",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    if(shown.isEmpty()) item {
                        EmptyPlaylist(if(query.isBlank())"留一面空白，等喜欢的歌。" else "没有找到这份歌单",if(query.isBlank())"点击右上角 +，创建第一份歌单。" else "换个关键词试试。")
                    }
                    when(style) {
                        PlaylistStyle.Cinema -> item { LazyRow(horizontalArrangement=Arrangement.spacedBy(15.dp)) { items(shown,key={it.id}) { item -> Column(Modifier.width(280.dp).clickable { open(item) }) {
                            StyledMixCover(style,item.name,item.id,Modifier.fillMaxWidth().height(220.dp));Text("${item.name} · 点击放映",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=10.dp))
                        } } } }
                        PlaylistStyle.Bookshelf -> item { LazyRow(Modifier.fillMaxWidth().height(310.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Bottom) { itemsIndexed(shown,key={_,it->it.id}) { index,item ->
                            BookSpine(item,index,{open(item)})
                        } } }
                        else -> itemsIndexed(shown,key={_,it->it.id}) { index,item ->
                            if(style==PlaylistStyle.Tape||style==PlaylistStyle.Desktop) {
                                Column(Modifier.fillMaxWidth().clickable { open(item) }) {
                                    StyledMixCover(style,item.name,item.id,Modifier.fillMaxWidth().height(if(style==PlaylistStyle.Tape)166.dp else 150.dp))
                                    Text(if(style==PlaylistStyle.Desktop)"窗口 ${index+1} / 点击打开" else "${item.name} · 点击打开",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
                                }
                            } else Surface(onClick={open(item)},color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(10.dp)) {
                                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    MixArt(item.id,Modifier.size(48.dp).clip(CircleShape));Column(Modifier.weight(1f).padding(horizontal=14.dp)) {
                                        Text(item.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=16.sp)
                                        Text("MIX ${index+1} · 打开歌单",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
                                    };Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            } else if(selected!=null) {
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(start=22.dp,end=22.dp,bottom=20.dp)) {
                    item {
                        StyledMixCover(style,selected.name,selected.id,Modifier.fillMaxWidth().height(210.dp))
                        Text(selected.name,fontSize=28.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=22.dp),maxLines=3)
                        Text("${current.size} 首 · ${current.sumOf { it.durationMs }/60_000} 分钟",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
                        Row(Modifier.padding(vertical=18.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button({current.firstOrNull()?.let { vm.play(it,current) }},Modifier.weight(1f),enabled=current.isNotEmpty()) { Icon(Icons.Default.PlayArrow,null,Modifier.size(18.dp));Text("播放全部") }
                            FilledTonalIconButton({val shuffled=current.shuffled();shuffled.firstOrNull()?.let {vm.play(it,shuffled)}},enabled=current.isNotEmpty()) { Icon(Icons.Default.Shuffle,"随机播放") }
                            FilledTonalIconButton({adding=true}) { Icon(Icons.Default.Add,"添加歌曲") }
                        }
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(if(managing)"调整顺序 / 移除" else "歌曲",fontSize=13.sp)
                            TextButton({managing=!managing}) { Text(if(managing)"完成" else "管理歌曲") }
                        }
                    }
                    if(current.isEmpty()) item { EmptyPlaylist("这份歌单还没有歌曲","点击 +，从音乐库中批量添加。") }
                    itemsIndexed(current,key={_,song->song.mediaStoreId}) { index,song ->
                        PlaylistTrack(song,index,playback.song?.mediaStoreId==song.mediaStoreId,onPlay={vm.play(song,current)}) {
                            if(managing) {
                                IconButton({vm.movePlaylistSong(selected.id,song.mediaStoreId,-1)},enabled=index>0,modifier=Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowUp,"上移") }
                                IconButton({vm.movePlaylistSong(selected.id,song.mediaStoreId,1)},enabled=index<current.lastIndex,modifier=Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowDown,"下移") }
                                IconButton({vm.removeFromPlaylist(selected.id,song.mediaStoreId)},Modifier.size(36.dp)) { Icon(Icons.Default.Close,"从歌单移除") }
                            } else {
                                IconButton({vm.favorite(song.mediaStoreId)},Modifier.size(40.dp)) { Icon(if(song.isFavorite)Icons.Default.Favorite else Icons.Default.FavoriteBorder,"收藏",tint=MaterialTheme.colorScheme.primary) }
                            }
                        }
                    }
                }
            } else Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) { TextButton({selectedId=null}) { Text("歌单已不存在，返回收藏") } }
            if(playback.queue.isNotEmpty()) Surface(onClick={showQueue=true},modifier=Modifier.padding(horizontal=12.dp,vertical=8.dp).fillMaxWidth(),color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(if(style==PlaylistStyle.Desktop||style==PlaylistStyle.Cinema)3.dp else 16.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.25f))) {
                Row(Modifier.padding(start=14.dp,end=5.dp,top=5.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.GraphicEq,null,tint=MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal=12.dp)) { Text(playback.song?.title.orEmpty(),fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${playback.song?.artist.orEmpty()} · ${playback.queue.size} 首待播",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1) }
                    IconButton({vm.player.toggle()}) { Icon(if(playback.playing)Icons.Default.Pause else Icons.Default.PlayArrow,if(playback.playing)"暂停" else "播放") }
                    Icon(Icons.Default.QueueMusic,"打开播放队列",Modifier.padding(end=8.dp))
                }
            }
        }
    }
    if(nameDialog) AlertDialog(onDismissRequest={nameDialog=false},title={Text(if(rename)"重命名歌单" else "新建歌单")},text={OutlinedTextField(name,{name=it.take(60)},label={Text("歌单名称")},singleLine=true)},confirmButton={TextButton({if(rename&&selected!=null)vm.renamePlaylist(selected.id,name)else vm.createPlaylist(name);nameDialog=false},enabled=name.isNotBlank()) {Text("保存")}},dismissButton={TextButton({nameDialog=false}){Text("取消")}})
    if(deleting&&selected!=null) AlertDialog(onDismissRequest={deleting=false},title={Text("删除「${selected.name}」？")},text={Text("仅删除歌单，音乐文件和当前播放队列会保留。")},confirmButton={TextButton({vm.deletePlaylist(selected);deleting=false;selectedId=null}){Text("删除")}},dismissButton={TextButton({deleting=false}){Text("取消")}})
    if(adding&&selected!=null) AddPlaylistSongs(songs,current.map {it.mediaStoreId}.toSet(),onClose={adding=false},onAdd={vm.addSongsToPlaylist(selected.id,it);adding=false})
    if(showQueue) PlaylistQueueSheet(vm,style,onClose={showQueue=false})
}

@Composable
private fun EmptyPlaylist(title: String,subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical=45.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.QueueMusic,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary)
        Text(title,fontSize=15.sp);Text(subtitle,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LibraryFeature(style: PlaylistStyle,lists: List<PlaylistEntity>,focused: PlaylistEntity,playing: Boolean,onOpen:(PlaylistEntity)->Unit,onCycle:()->Unit,onFocus:(PlaylistEntity)->Unit) {
    val c=MaterialTheme.colorScheme
    when(style) {
        PlaylistStyle.Tape -> Unit
        PlaylistStyle.Turntable,PlaylistStyle.Desktop -> Column {
            StyledMixCover(style,focused.name,focused.id,Modifier.fillMaxWidth().height(if(style==PlaylistStyle.Turntable)290.dp else 210.dp).clickable {onOpen(focused)},spinning=playing)
            Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(focused.name,Modifier.weight(1f),fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                TextButton(onCycle) { Text(if(style==PlaylistStyle.Turntable)"换一张 →" else "切换窗口 →") }
            }
        }
        PlaylistStyle.Cinema -> Surface(color=c.surfaceVariant,shape=RoundedCornerShape(2.dp)) {
            Column {
                CanvasClapper()
                Column(Modifier.padding(20.dp)) {
                    Text("XSTAR PICTURES PRESENTS",fontSize=8.sp,color=c.primary,fontFamily=FontFamily.Monospace,letterSpacing=1.sp)
                    Text("平凡生活\n不平凡的 B 面",fontSize=29.sp,fontFamily=FontFamily.Serif,modifier=Modifier.padding(vertical=15.dp))
                    OutlinedButton({onOpen(lists.random())}) { Icon(Icons.Default.PlayArrow,null,Modifier.size(17.dp));Text("随机放映") }
                }
            }
        }
        PlaylistStyle.Bookshelf -> Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("“\n读到这里，\n请戴上耳机。",Modifier.weight(1f),fontSize=25.sp,fontFamily=FontFamily.Serif,lineHeight=34.sp)
            OutlinedButton({onOpen(lists.random())},Modifier.size(96.dp).rotate(12f),shape=CircleShape,border=BorderStroke(1.dp,c.primary),contentPadding=PaddingValues(4.dp)) { Text("随手\n抽一本 ↗",fontSize=14.sp) }
        }
        PlaylistStyle.Radar -> {
            val transition=rememberInfiniteTransition(label="radar")
            val angle by transition.animateFloat(0f,360f,infiniteRepeatable(tween(9000,easing=LinearEasing)),label="scan")
            Column {
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    RadarDrawing(Modifier.fillMaxSize(),angle)
                    lists.forEachIndexed { i,item ->
                        val theta=i*2*Math.PI/lists.size-1.4
                        val radius=if(i%2==0).32f else .22f
                        val x=.5f+cos(theta).toFloat()*radius;val y=.5f+sin(theta).toFloat()*radius
                        val active=focused.id==item.id
                        Box(Modifier.offset(x=maxWidth*x-24.dp,y=maxHeight*y-24.dp).size(48.dp).clip(CircleShape).clickable {onFocus(item)}.then(if(active)Modifier.border(1.dp,c.primary,CircleShape).background(c.primary.copy(alpha=.12f))else Modifier),contentAlignment=Alignment.Center) {
                            Text("${i+1}",color=c.primary,fontSize=12.sp,fontFamily=FontFamily.Monospace)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable {onOpen(focused)}) {
                        Text("SIGNAL LOCKED",fontSize=8.sp,color=c.primary,fontFamily=FontFamily.Monospace,letterSpacing=1.sp)
                        Text("${focused.name} ↗",fontSize=19.sp,modifier=Modifier.padding(top=7.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onCycle) { Text("扫描 →") }
                }
                HorizontalDivider(Modifier.padding(top=15.dp),color=c.primary.copy(alpha=.5f))
            }
        }
    }
}

@Composable
private fun CanvasClapper() {
    val c=MaterialTheme.colorScheme
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(26.dp)) {
        drawRect(c.inverseSurface)
        repeat(12) { i -> val x=i*size.width/10;val p=androidx.compose.ui.graphics.Path().apply {moveTo(x,0f);lineTo(x+size.width/18,0f);lineTo(x,size.height);lineTo(x-size.width/18,size.height);close()};drawPath(p,c.inverseOnSurface) }
    }
}

@Composable
private fun BookSpine(item: PlaylistEntity,index: Int,onClick:()->Unit) {
    val c=MaterialTheme.colorScheme;val bright=index%2==1
    val ink=if(bright)c.onPrimary else c.onSurface
    Column(Modifier.width(82.dp).height(if(index%3==0)280.dp else if(index%3==1)245.dp else 300.dp).clip(RoundedCornerShape(topStart=5.dp)).background(if(bright)c.primary else c.surfaceVariant).border(1.dp,c.outlineVariant).clickable(onClick=onClick).padding(vertical=15.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Text("VOL. ${(index+1).toString().padStart(2,'0')}",color=ink,fontSize=8.sp,fontFamily=FontFamily.Monospace)
        HorizontalDivider(Modifier.padding(vertical=12.dp),color=ink.copy(alpha=.3f))
        item.name.take(8).forEach { Text(it.toString(),fontSize=21.sp,color=ink,fontFamily=FontFamily.Serif,lineHeight=25.sp) }
        Spacer(Modifier.weight(1f));Icon(Icons.Default.MusicNote,null,Modifier.size(20.dp),tint=ink.copy(alpha=.7f))
    }
}

@Composable
private fun PlaylistTrack(song: SongEntity,index: Int,active: Boolean,onPlay:()->Unit,actions:@Composable RowScope.()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=72.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(if(active)"♪" else (index+1).toString().padStart(2,'0'),Modifier.width(26.dp),fontSize=11.sp,color=if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,fontFamily=FontFamily.Monospace)
        Column(Modifier.weight(1f).clickable(onClick=onPlay).padding(vertical=14.dp)) {
            Text(song.title,fontSize=14.sp,color=if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(song.artist,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=5.dp))
        }
        actions()
    }
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPlaylistSongs(songs: List<SongEntity>,existing: Set<Long>,onClose:()->Unit,onAdd:(List<Long>)->Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var chosen by remember { mutableStateOf(emptySet<Long>()) }
    val available=remember(songs,existing,query) { songs.filter { it.mediaStoreId !in existing && (it.title+it.artist).contains(query,ignoreCase=true) } }
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal=20.dp)) {
            Text("添加歌曲",fontSize=23.sp,fontWeight=FontWeight.SemiBold)
            OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(vertical=14.dp),placeholder={Text("搜索音乐库")},singleLine=true,leadingIcon={Icon(Icons.Default.Search,null)})
            LazyColumn(Modifier.weight(1f)) {
                if(available.isEmpty()) item { EmptyPlaylist("没有可添加的歌曲","可以先扫描音乐库，或试试其他关键词。") }
                items(available,key={it.mediaStoreId}) { song ->
                    Row(Modifier.fillMaxWidth().clickable {chosen=if(song.mediaStoreId in chosen)chosen-song.mediaStoreId else chosen+song.mediaStoreId}.padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(song.title,maxLines=1,overflow=TextOverflow.Ellipsis);Text(song.artist,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        Checkbox(song.mediaStoreId in chosen,{checked->chosen=if(checked)chosen+song.mediaStoreId else chosen-song.mediaStoreId})
                    }
                }
            }
            Button({onAdd(chosen.toList())},Modifier.fillMaxWidth().padding(vertical=12.dp),enabled=chosen.isNotEmpty()) {Text("加入歌单 · ${chosen.size} 首")}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistQueueSheet(vm: MusicViewModel,style: PlaylistStyle,onClose:()->Unit) {
    val state by vm.playback.collectAsStateWithLifecycle()
    var clear by remember { mutableStateOf(false) }
    val radius=if(style==PlaylistStyle.Desktop||style==PlaylistStyle.Cinema)6.dp else 26.dp
    ModalBottomSheet(onDismissRequest=onClose,shape=RoundedCornerShape(topStart=radius,topEnd=radius)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.75f).padding(horizontal=20.dp)) {
            StyledMixCover(style,state.song?.title ?: "片刻安静",state.song?.mediaStoreId?.let { kotlin.math.abs(it % 3) } ?: 0L,
                Modifier.fillMaxWidth().height(115.dp).padding(bottom=12.dp))
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("接下来播放",fontSize=23.sp,fontWeight=FontWeight.SemiBold);Text("${state.queue.size} 首 · ${style.title}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=5.dp)) }
                TextButton({clear=true},enabled=state.queue.isNotEmpty()) {Text("清空")}
            }
            LazyColumn(Modifier.weight(1f).padding(top=14.dp)) {
                if(state.queue.isEmpty()) item {EmptyPlaylist("队列已清空","歌单还在，随时可以重新播放。")}
                itemsIndexed(state.queue,key={index,song->"${song.mediaStoreId}:$index"}) {index,song ->
                    PlaylistTrack(song,index,index==state.index,onPlay={vm.player.jumpTo(index)}) {
                        IconButton({if(state.queue.size==1)vm.clearPlaybackQueue()else vm.player.remove(index)}) {Icon(Icons.Default.Close,"从队列移除")}
                    }
                }
            }
        }
    }
    if(clear) AlertDialog(onDismissRequest={clear=false},title={Text("清空当前队列？")},text={Text("播放会停止，已保存的歌单不受影响。")},confirmButton={TextButton({vm.clearPlaybackQueue();clear=false}) {Text("清空并停止")}},dismissButton={TextButton({clear=false}){Text("取消")}})
}
