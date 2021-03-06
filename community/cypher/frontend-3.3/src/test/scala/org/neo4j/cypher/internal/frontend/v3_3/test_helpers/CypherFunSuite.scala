/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cypher.internal.frontend.v3_3.test_helpers

import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

import scala.reflect.Manifest

@RunWith(classOf[JUnitRunner])
abstract class CypherFunSuite
  extends Suite
  with Assertions
  with CypherTestSupport
  with MockitoSugar
  with FunSuiteLike
  with Matchers
  with BeforeAndAfterEach {

  override protected def beforeEach() {
    initTest()
  }

  override protected def afterEach() {
    stopTest()
  }

  def argCaptor[T <: AnyRef](implicit manifest: Manifest[T]): ArgumentCaptor[T] = {
    ArgumentCaptor.forClass(manifest.runtimeClass.asInstanceOf[Class[T]])
  }
}

trait TestName extends Suite {
  final def testName = __testName.get

  private var __testName: Option[String] = None

  override protected def runTest(testName: String, args: Args): Status = {
    __testName = Some(testName)
    try {
      super.runTest(testName, args)
    } finally {
      __testName = None
    }
  }
}
