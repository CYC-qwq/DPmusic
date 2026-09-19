package com.dpmusic.app.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** 全局唯一 DataStore 实例（收藏 / 历史 / 设置共用） */
val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "dpmusic_store")