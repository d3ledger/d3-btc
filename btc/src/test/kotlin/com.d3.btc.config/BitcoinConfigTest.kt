package com.d3.btc.config

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockitokotlin2.mock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BitcoinConfigTest {

    /**
     * @given config with one host
     * @when hosts are extracted
     * @then only one host is extracted
     */
    @Test
    fun testExtractHostsOneHost() {
        val bitcoinConfig = mock<BitcoinConfig> {
            on { hosts } doReturn "123"
        }
        val hosts = BitcoinConfig.extractHosts(bitcoinConfig)
        assertEquals(1, hosts.size)
        assertEquals("123", hosts[0])
    }

    /**
     * @given config with two hosts
     * @when hosts are extracted
     * @then two hosts are extracted
     */
    @Test
    fun testExtractHostsFewHosts() {
        val bitcoinConfig = mock<BitcoinConfig> {
            on { hosts } doReturn "123,456"
        }
        val hosts = BitcoinConfig.extractHosts(bitcoinConfig)
        assertEquals(2, hosts.size)
        assertEquals("123", hosts[0])
        assertEquals("456", hosts[1])
    }


    /**
     * @given config with two hosts and extra space
     * @when hosts are extracted
     * @then two hosts are extracted
     */
    @Test
    fun testExtractHostsExtraSpaces() {
        val bitcoinConfig = mock<BitcoinConfig> {
            on { hosts } doReturn "123 , 456"
        }
        val hosts = BitcoinConfig.extractHosts(bitcoinConfig)
        assertEquals(2, hosts.size)
        assertEquals("123", hosts[0])
        assertEquals("456", hosts[1])
    }

    /**
     * @given empty line
     * @when extractCommaSeparatedList() is called against the line
     * @then extractCommaSeparatedList() returns empty list
     */
    @Test
    fun extractCommaSeparatedListEmpty() {
        assertTrue(extractCommaSeparatedList("").isEmpty())
    }

    /**
     * @given line with one element 'a'
     * @when extractCommaSeparatedListOneElement() is called against the line
     * @then extractCommaSeparatedListOneElement() returns one-element list with 'a' item
     */
    @Test
    fun extractCommaSeparatedListOneElement() {
        val list = extractCommaSeparatedList("a")
        assertEquals(1, list.size)
        assertEquals("a", list[0])
    }

    /**
     * @given line with bad formatting
     * @when extractCommaSeparatedList() is called against the line
     * @then extractCommaSeparatedList() returns a list with all non-empty items from the line
     */
    @Test
    fun extractCommaSeparatedListThreeElementBadFormat() {
        val list = extractCommaSeparatedList("a,b  ,c,, d,")
        assertEquals(4, list.size)
        assertEquals("a", list[0])
        assertEquals("b", list[1])
        assertEquals("c", list[2])
        assertEquals("d", list[3])
    }
}
