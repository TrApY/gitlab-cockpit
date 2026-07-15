package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests for [filterMembers]: the blank-query short-circuit, the case-insensitive substring match
 * over name OR username, order preservation and the username-only match for members with a blank name.
 */
class MemberFilterTest {

    private fun user(id: Long, username: String, name: String) = GitLabUser(id, username, name)

    private val members = listOf(
        user(1, "jdoe", "John Doe"),
        user(2, "asmith", "Alice Smith"),
        user(3, "bob", "Bob Jones"),
        user(4, "charlie", ""),
    )

    @Test
    fun `blank or whitespace query returns every member unchanged`() {
        assertEquals(members, filterMembers(members, ""))
        assertEquals(members, filterMembers(members, "   "))
    }

    @Test
    fun `matches on username substring case-insensitively`() {
        assertEquals(listOf(4L), filterMembers(members, "char").map { it.id })
        assertEquals(listOf(4L), filterMembers(members, "CHAR").map { it.id })
    }

    @Test
    fun `matches on name substring the username does not contain`() {
        assertEquals(listOf(2L), filterMembers(members, "alice").map { it.id })
    }

    @Test
    fun `matches name OR username and preserves input order`() {
        // "jo" hits "John Doe" and "Bob Jones" (both by name), in member order.
        assertEquals(listOf(1L, 3L), filterMembers(members, "jo").map { it.id })
    }

    @Test
    fun `a blank-name member is matched only through its username`() {
        assertEquals(listOf(4L), filterMembers(members, "harli").map { it.id })
    }

    @Test
    fun `no match yields an empty list`() {
        assertEquals(emptyList<GitLabUser>(), filterMembers(members, "zzz"))
    }

    @Test
    fun `the query is trimmed before matching`() {
        assertEquals(listOf(2L), filterMembers(members, "  alice ").map { it.id })
    }
}
