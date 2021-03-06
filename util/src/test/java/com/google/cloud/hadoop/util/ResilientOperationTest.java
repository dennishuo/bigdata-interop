/**
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.util;


import static org.junit.Assert.assertEquals;

import com.google.api.client.testing.util.MockSleeper;
import com.google.api.client.util.BackOff;
import com.google.cloud.hadoop.util.ResilientOperation.CheckedCallable;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;

/** Unit tests for {@link ResilientOperation}. */
@RunWith(JUnit4.class)
public class ResilientOperationTest {
  @Rule
  public ExpectedException exception = ExpectedException.none();

  @Test
  public void testValidCallHasNoRetries() throws Exception {
    MockSleeper sleeper = new MockSleeper();
    CallableTester<Exception> callTester = new CallableTester<>(new ArrayList<Exception>());
    BackOff backoff = new RetryBoundedBackOff(3, new BackOffTester());
    ResilientOperation.retry(callTester, backoff, RetryDeterminer.DEFAULT, Exception.class,
        sleeper);
    assertEquals(callTester.timesCalled(), 1);
    assertEquals(sleeper.getCount(), 0);
  }

  @Test
  public void testCallFailsOnBadException() throws Exception {
    MockSleeper sleeper = new MockSleeper();
    ArrayList<Exception> exceptions = new ArrayList<>();
    exceptions.add(new IllegalArgumentException("FakeException"));
    CallableTester<Exception> callTester = new CallableTester<>(exceptions);
    BackOff backoff = new RetryBoundedBackOff(3, new BackOffTester());
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage("FakeException");
    try {
      ResilientOperation.retry(callTester, backoff, RetryDeterminer.DEFAULT, Exception.class,
          sleeper);
    } finally {
      assertEquals(callTester.timesCalled(), 1);
      verifySleeper(sleeper, 0);
    }
  }

  @Test
  public void testCallRetriesAndFails() throws Exception {
    MockSleeper sleeper = new MockSleeper();
    ArrayList<Exception> exceptions = new ArrayList<>();
    exceptions.add(new SocketTimeoutException("socket"));
    exceptions.add(new SocketTimeoutException("socket"));
    exceptions.add(new IllegalArgumentException("FakeException"));
    CallableTester<Exception> callTester = new CallableTester<>(exceptions);
    BackOff backoff = new RetryBoundedBackOff(5, new BackOffTester());
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage("FakeException");
    try {
      ResilientOperation.retry(callTester, backoff, RetryDeterminer.DEFAULT, Exception.class,
          sleeper);
    } finally {
      assertEquals(3, callTester.timesCalled());
      verifySleeper(sleeper, 2);
    }
  }
  
  @Test
  public void testCallRetriesAndFailsWithSocketErrors() throws Exception {
    MockSleeper sleeper = new MockSleeper();
    ArrayList<IOException> exceptions = new ArrayList<>();
    exceptions.add(new SocketTimeoutException("socket"));
    exceptions.add(new SocketTimeoutException("socket"));
    exceptions.add(new IOException("FakeException"));
    CallableTester<IOException> callTester = new CallableTester<>(exceptions);
    BackOff backoff = new RetryBoundedBackOff(5, new BackOffTester());
    exception.expect(IOException.class);
    exception.expectMessage("FakeException");
    try {
      ResilientOperation.retry(callTester, backoff, RetryDeterminer.SOCKET_ERRORS,
          IOException.class, sleeper);
    } finally {
      assertEquals(3, callTester.timesCalled());
      verifySleeper(sleeper, 2);
    }
  }

  public void verifySleeper(MockSleeper sleeper, int retry) {
    assertEquals(sleeper.getCount(), retry);
    if (retry == 0) {
      return;
    }
    assertEquals(sleeper.getLastMillis(), (long) Math.pow(2, retry));
  }

  @Test
  public void testCallMaxRetries() throws Exception {
    MockSleeper sleeper = new MockSleeper();
    ArrayList<Exception> exceptions = new ArrayList<>();
    exceptions.add(new SocketTimeoutException("socket"));
    exceptions.add(new SocketTimeoutException("socket2"));
    exceptions.add(new SocketTimeoutException("socket3"));
    CallableTester<Exception> callTester = new CallableTester<>(exceptions);
    BackOff backoff = new RetryBoundedBackOff(2, new BackOffTester());
    exception.expect(SocketTimeoutException.class);
    exception.expectMessage("socket3");
    try {
      ResilientOperation.retry(callTester, backoff, RetryDeterminer.DEFAULT, Exception.class,
          sleeper);
    } finally {
      assertEquals(callTester.timesCalled(), 3);
      verifySleeper(sleeper, 2);
    }
  }

  @Test
  public void testCallRetriesAndSucceeds() throws Exception {
    MockSleeper sleeper = new MockSleeper();
    ArrayList<Exception> exceptions = new ArrayList<>();
    exceptions.add(new SocketTimeoutException("socket"));
    exceptions.add(new SocketTimeoutException("socket2"));
    exceptions.add(new SocketTimeoutException("socket3"));
    CallableTester<Exception> callTester = new CallableTester<>(exceptions);
    BackOff backoff = new RetryBoundedBackOff(3, new BackOffTester());
    assertEquals(
        3,
        ResilientOperation.retry(callTester, backoff, RetryDeterminer.DEFAULT, Exception.class,
            sleeper).intValue());
    assertEquals(callTester.timesCalled(), 4);
    verifySleeper(sleeper, 3);
  }
  
  private class CallableTester<X extends Exception> implements CheckedCallable<Integer, X> {
    int called = 0;
    ArrayList<X> exceptions = null;

    public CallableTester(ArrayList<X> exceptions) {
      this.exceptions = exceptions;
    }

    @Override
    public Integer call() throws X {
      if (called < exceptions.size()) {
        throw exceptions.get(called++);
      }
      return called++;
    }

    public int timesCalled() {
      return called;
    }
  }

  private class BackOffTester implements BackOff {
    int counter = 1;

    @Override
    public void reset() {
      counter = 1;
    }

    @Override
    public long nextBackOffMillis() {
      counter *= 2;
      return counter;
    }
  }
}
