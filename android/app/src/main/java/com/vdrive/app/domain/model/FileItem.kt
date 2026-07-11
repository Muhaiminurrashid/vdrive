package com.vdrive.app.domain.model

data class FileItem(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val size: Long = 0,
    val mimeType: String = "",
    val downloadUrl: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)
