package com.example.gradle.mcp.dependency

class NameDictionary {
    private val nameToId = LinkedHashMap<String, Int>()
    private val idToName = ArrayList<String>()

    fun intern(name: String): Int =
        nameToId.getOrPut(name) {
            val id = idToName.size
            idToName.add(name)
            id
        }

    fun lookup(name: String): Int? = nameToId[name]

    fun size(): Int = idToName.size

    fun names(): List<String> = idToName.toList()
}