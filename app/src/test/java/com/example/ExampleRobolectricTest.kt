package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Notes", appName)
  }

  @Test
  fun `test secure user id generator format and length`() {
    val userId = com.example.firebase.firestore.FirestoreRepository.generateSecureUserId()
    org.junit.Assert.assertTrue("User ID must start with usr_", userId.startsWith("usr_"))
    org.junit.Assert.assertTrue("User ID must be at least 8 characters", userId.length >= 8)
  }
}
