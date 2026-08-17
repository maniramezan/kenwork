package io.github.maniramezan.kenwork.mutations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MutationKeyTest {
    @Test
    fun `keys with the same value are equal`() {
        assertEquals(MutationKey("like:video:42"), MutationKey("like:video:42"))
    }

    @Test
    fun `keys with different values are not equal`() {
        assertNotEquals(MutationKey("like:video:42"), MutationKey("like:video:43"))
    }

    @Test
    fun `of joins parts with a colon`() {
        assertEquals(MutationKey("like:video:42"), MutationKey.of("like", "video", 42))
    }
}
