package com.jollywhoppers.atproto.server

object Tid {
    private val BASE32 = "234567abcdefghijklmnopqrstuvwxyz"
    fun generate(): String {
        val now = System.currentTimeMillis()
        val timestamp = java.lang.Long.toString(now, 32)
        val random = (1..4).map { BASE32[(Math.random() * BASE32.length).toInt()] }.joinToString("")
        return "$timestamp$random"
    }
}
