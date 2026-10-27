package com.hop.drop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.hop.drop.Language

@Composable internal fun filesText(count: Int): String = Language.files(LocalContext.current, count)
@Composable internal fun fileObjectsText(count: Int): String = Language.fileObjects(LocalContext.current, count)
@Composable internal fun foldersText(count: Int): String = Language.folders(LocalContext.current, count)
@Composable internal fun sizeText(bytes: Long): String = Language.size(LocalContext.current, bytes)
@Composable internal fun durationText(seconds: Long): String = Language.duration(LocalContext.current, seconds)
@Composable internal fun ltr(value: String): String = Language.ltr(LocalContext.current, value)
