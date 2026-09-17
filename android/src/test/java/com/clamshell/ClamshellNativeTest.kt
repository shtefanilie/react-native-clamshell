package com.clamshell

import org.junit.Assert.assertEquals
import org.junit.Test

class ClamshellNativeTest {
  @Test
  fun scaffoldLoads() {
    assertEquals("com.clamshell", HybridClamshell::class.java.packageName)
  }
}
