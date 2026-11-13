package com.fograin.launcher.model

/**
 * Static fixtures that the core does not feed yet.
 *
 * Instances, tasks and game config all come from `fg-ffi` in M4. Accounts land
 * in M7 and version news is a curated list until the news feed is wired; both
 * mirror the desktop shell so all shells show the same information.
 */
object SampleData {
    data class Account(
        val name: String,
        val initial: String,
        val kind: String,
        val isOnline: Boolean,
    )

    data class NewsItem(
        val id: String,
        val title: String,
        val detail: String,
        val versionId: String,
    )

    val account = Account(
        name = "Steve_Fog",
        initial = "S",
        kind = "离线账户",
        isOnline = true,
    )

    val news: List<NewsItem> = listOf(
        NewsItem(
            id = "release-1.21.5",
            title = "Java 版 1.21.5 正式发布",
            detail = "新增生物群系变种 · 今天",
            versionId = "1.21.5",
        ),
        NewsItem(
            id = "snapshot-25w14a",
            title = "快照 25w14a",
            detail = "实验性功能 · 3 天前",
            versionId = "25w14a",
        ),
    )
}
