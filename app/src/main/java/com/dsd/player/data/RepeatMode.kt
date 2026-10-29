// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.data

/**
 * 播放模式（队列末尾怎么走）：
 *  - ORDER 顺序播放：播完最后一首就停
 *  - LIST  列表循环：播完最后一首回到第一首
 *  - ONE   单曲循环：一直重复当前这首
 */
enum class RepeatMode(val label: String) {
    ORDER("顺序播放"),
    LIST("列表循环"),
    ONE("单曲循环");

    fun next(): RepeatMode = when (this) {
        ORDER -> LIST
        LIST -> ONE
        ONE -> ORDER
    }

    companion object {
        fun of(name: String?): RepeatMode =
            entries.firstOrNull { it.name == name } ?: ORDER
    }
}
