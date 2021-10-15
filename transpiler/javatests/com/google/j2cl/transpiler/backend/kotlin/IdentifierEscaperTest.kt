/*
 * Copyright 2021 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.transpiler.backend.kotlin

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class IdentifierEscaperTest {
  @Test
  fun identifierSourceString_simple() {
    assertThat("foo".identifierSourceString).isEqualTo("foo")
  }

  @Test
  fun identifierSourceString_hardKeyword() {
    assertThat("is".identifierSourceString).isEqualTo("`is`")
  }

  @Test
  fun identifierSourceString_withInvalidChar() {
    assertThat("foo\$bar".identifierSourceString).isEqualTo("`foo\$bar`")
  }

  @Test
  fun identifierSourceString_startingWithDigit() {
    assertThat("1foo".identifierSourceString).isEqualTo("`1foo`")
  }

  @Test
  fun packageNameSourceString() {
    assertThat("foo.is.bar".packageNameSourceString).isEqualTo("foo.`is`.bar")
  }
}
