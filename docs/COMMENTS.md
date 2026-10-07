# 歌曲评论：三平台实测与楼中楼回复

> 文件：`core/model/Comments.kt`、`core/net/{WyApi,QqApi,KgApi}.kt`、`ui/components/CommentsSheet.kt`
> 原则：**接口字段全部终端实测校准，不臆造**。下面每条"结论"都附带实测证据。

## 1. 三平台能力矩阵（实测）

| 平台 | 评论列表 | 楼中楼回复 | 实测证据 |
| --- | --- | --- | --- |
| **网易云** | ✅ 可用 | ⚠️ **部分可用**（见 §3） | `/api/v1/resource/comments/R_SO_4_186016` → `code=200`、`total=1970254` |
| **QQ 音乐** | ✅ 可用（**修好了一个静默 bug**，见 §2） | ❌ **接口不提供** | `topid` 改数字 id 后 `total=230665`；评论项字段里**没有** `commentcount`/`replylist` |
| **酷狗** | ❌ 不可用 | ❌ | 见 §4 |

## 2. QQ：`topid` 必须传数字 songId（本项目此前的静默 bug）

### 症状

QQ 评论面板**永远显示"暂无评论"**，但接口 `code=0`、无报错 —— 典型的静默空结果。

### 根因

`fcg_global_comment_h5.fcg` 的 `topid` 参数需要**数字 songId**，而代码传的是 **MID**（如 `0039MnYb0qxYhV`）：

```
传 MID   → code=0, comment.commenttotal=0, commentlist=null   ← 一直空
传 数字id → code=0, comment.commenttotal=230665, commentlist=15 ✅
```

且响应里 `allow_comment=1`、`topic_name='想你'`，看起来"一切正常" —— 所以这个 bug 很难从日志发现。

### 修复

```kotlin
// QqApi.comments
val numericId = resolveNumericSongId(songId) ?: return null
// URL 里 topid=$numericId（不再用 urlEnc(songId)）

/** 纯数字 id 原样返回；否则走 music.trackInfo.UniformRuleCtrl（匿名可调） */
private suspend fun resolveNumericSongId(id: String): String? {
    if (id.isBlank()) return null
    if (id.all { it.isDigit() }) return id
    return runCatching { songIdsByMids("", listOf(id))[id]?.toString() }.getOrNull()
}
```

复用了仓库里**已有的** `songIdsByMids(cookie, mids)`（原本用于红心同步），传空 Cookie 即可匿名调用，无需新增接口代码。

### 实测验证（Python 复算同一链路）

| songmid | 解析出的数字 id | total | hot | items |
| --- | --- | --- | --- | --- |
| `0039MnYb0qxYhV`（晴天） | 97773 | 230665 | 15 | 19 |
| `004Z8Ihr0JIu5s` | — | 102159 | 15 | 20 |
| `001OyHbk2MSIi4` | — | 51774 | 15 | 18 |

分页一致性：第 1 页 19 条 / 第 2 页 20 条，**交集 0 条**（无重复）。

### 另外修正

补齐了官方示例里的通用参数（`inCharset` / `outCharset` / `notice` / `platform` / `needNewCode`），与匿名网页端请求一致。

## 3. 网易云：`beReplied` 是"被回复的那一条"，不是完整楼层

### 字段结构（实测）

评论列表**每条评论**可能带 `beReplied` 数组，元素字段：

| 字段 | 说明 |
| --- | --- |
| `beRepliedCommentId` | 被回复评论的 id |
| `content` | 被回复的内容 |
| `user.nickname` / `user.avatarUrl` | 被回复者 |
| **（无 `time`）** | ⚠️ **实测没有该字段**，故 `CommentReply.timeMs = 0`，UI 跳过时间展示 |
| 无 `likedCount` | 故 `CommentReply.likedCount = 0` |

### 关键限制（勿按"完整楼中楼"设计 UI）

实测 100 条评论里**只有约 3 条**带 `beReplied`，且每条的 `beReplied` **最多 1 个元素**：

```
解析评论 115 条，其中回复 4 条
主评论 438479088「12年前，周杰伦带着他的《发如雪》闯进了」
   └ 网易云音乐: 「【周杰伦出道15周年】十年前，一个咬字不清的男 (id=45099584, timeMs=0)
```

也就是说：**这是"这条评论在回复谁"的回显，不是该评论收到的回复列表**。

### 为什么不去调楼层接口

试着拿完整楼层（`/api/resource/comment/floor/get`）全部失败：

| 请求 | 结果 |
| --- | --- |
| GET `?parentCommentId=&limit=&time=0` | `{"msg":"参数错误","code":400}` |
| POST 同上（+ `type=0` / 无 `time` / `time=-1` / `ownerUserId=`） | 全部 `code:400` |
| `/api/v1/.../floor/get`、`/api/comment/floor/get` | `code:404 接口未找到` |
| `/api/v1/resource/comments/hot/...`、`/api/v2/...` | `code:404` |

该接口属于 webapi（需 `eapi`/`weapi` 加密参数），本项目未实现加密层，**故不硬凑**。
另外评论列表接口**不返回 `showFloorComment`**（响应顶层有 `showFloorComment` 字段但恒为 `null`），也无法从中拿回复数。

## 4. 酷狗：评论接口整体失效（明确不支持）

| 尝试 | 结果 |
| --- | --- |
| `mcomment.kugou.com ... commentsv2/getCommentWithLike` | `{"msg":"获取错误请重试","err_code":10002}` |
| `mcomment.kugou.com ... comments/getComments`（带 hash） | `{"err_code":10002,"msg":"未传入hash"}` |
| 加 `signature`（自造 MD5 组合） | 仍 `err_code:10002` |
| `wwwapi.kugou.com/yy/index.php?r=comment/get_comments` | `Access Deny !!` |
| `m.kugou.com/app/i/getSongComment.php` | `No Action Found!` |
| `curcomment.kugou.com` | DNS 不解析 |

签名算法未公开且会随客户端版本变化，**故不实现**；`KgApi.comments()` 继承 `PlatformApi` 默认实现返回 `null`，UI 显示「该平台暂不支持评论」。

## 5. 数据模型

```kotlin
data class CommentReply(
    val id: String, val nickname: String, val avatarUrl: String, val content: String,
    val timeMs: Long = 0L,        // 网易云无此字段 → 0，UI 跳过展示
    val likedCount: Int = 0,
)

data class CommentItem(
    ...,
    val replies: List<CommentReply> = emptyList(),
    val replyCount: Int = 0,      // 服务端总数；0=未知（QQ/网易云都拿不到）
) {
    val displayReplyCount: Int get() = if (replyCount > 0) replyCount else replies.size
    val hasReplies: Boolean get() = replies.isNotEmpty()
}
```

**两个默认值都给了 `= 默认`**，因此老构造点（如 QQ 的 parseList）无需改动即可编译。

## 6. UI：回复默认折叠

`CommentsSheet.kt` 新增三个私有组件：

| 组件 | 职责 |
| --- | --- |
| `ReplyToggle` | 「查看 N 条回复」+ `ExpandMore/ExpandLess` 箭头，整行可点 |
| `RepliesBlock` | `AnimatedVisibility` 容器；展开 240ms `expandVertically` + 180ms 淡入（延迟 40ms），收起 180ms；左侧 2dp 竖线做视觉分层；缩进 48dp 对齐正文起点（头像 36 + 间距 12） |
| `ReplyRow` | 24dp 小头像 + 昵称（onSurfaceVariant）+ 内容（bodySmall）；**时间仅在 `timeMs > 0` 时显示** |

关键实现点：

- **展开状态用 `remember(c.id)`**：按评论 id 记忆，列表滚动 / 重排 / 加载下一页都不会串位。
- **默认折叠 = 不组合子树**：`AnimatedVisibility(visible = false)` 时 `RepliesBlock` 内容完全不进入 composition，长列表零额外开销。
- **无回复的评论不渲染任何入口**：`if (c.hasReplies)` 双重守卫（Toggle 与 Block 各一处）。
- 空态文案更新为「目前支持网易云 / QQ 音乐；酷狗评论接口需签名，暂不可用」。

## 7. 验收要点

- [ ] **QQ 音乐歌曲**点开评论：能正常列出评论（修复前恒为「暂无评论」），条数与网页端同量级；
- [ ] 评论数与「加载更多」的翻页不重复、不跳条；
- [ ] **网易云**带回复的评论显示「查看 1 条回复」入口，点击后**平滑展开**（不是硬切换），箭头同步翻转；
- [ ] 回复默认**收起**（首次进入不应自动展开）；
- [ ] 滚动列表后再滚回来，已展开的评论**保持展开**，未展开的**保持收起**；
- [ ] 无回复的评论**不显示**任何回复入口；
- [ ] 回复行**不显示时间**（网易云不提供该字段，不应出现「1970 年」之类的错误时间）；
- [ ] 酷狗歌曲点开评论：显示「该平台暂不支持评论」空态，不报错、不崩溃。

## 8. 排查记录

1. **"接口没坏"的假象**：QQ 返回 `code=0` 且 `allow_comment=1`，从响应本身完全看不出 `topid` 传错了。**判断"接口挂了"之前，先把参数按官方示例逐个对齐**——本例只是 id 类型不对。
2. **`cmd=9` 的误导**：`cmd=9` 确实返回 `total=4000` 的列表，看着像楼层接口；但**换任意 `commentid` 结果完全相同**，说明它是"热评流"而非楼中楼。定位这类问题的办法是**固定其它参数、只改目标参数，看结果是否随之变化**。
3. **不要在 `Kotlin` 里写未验证的签名算法**：酷狗签名尝试失败后直接放弃实现，比塞一个"可能今天能跑、明天就 403"的算法更负责。
4. **默认值省事**：给 `CommentItem` 新字段加 `= emptyList()` / `= 0`，让 QQ 侧解析代码一行不用改。
