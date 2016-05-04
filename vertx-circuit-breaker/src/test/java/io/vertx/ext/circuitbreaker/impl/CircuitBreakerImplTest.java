/*
 * Copyright (c) 2011-2016 The original author or authors
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *      The Eclipse Public License is available at
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *      The Apache License v2.0 is available at
 *      http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.circuitbreaker.impl;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.circuitbreaker.CircuitBreaker;
import io.vertx.ext.circuitbreaker.CircuitBreakerOptions;
import io.vertx.ext.circuitbreaker.CircuitBreakerState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.jayway.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class CircuitBreakerImplTest {
  private Vertx vertx;
  private CircuitBreaker breaker;

  @Before
  public void setUp() {
    vertx = Vertx.vertx();
  }

  @After
  public void tearDown() {
    if (breaker != null) {
      breaker.close();
    }
    AtomicBoolean completed = new AtomicBoolean();
    vertx.close(ar -> completed.set(ar.succeeded()));
    await().untilAtomic(completed, is(true));
  }

  @Test
  public void testCreationWithDefault() {
    breaker = CircuitBreaker.create("name", vertx);
    assertThat(breaker.name()).isEqualTo("name");
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
  }

  @Test
  public void testSynchronousOk() {
    breaker = CircuitBreaker.create("test", vertx, new CircuitBreakerOptions());
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

    AtomicBoolean called = new AtomicBoolean();
    breaker.executeBlocking(v -> called.set(true));

    assertThat(called.get()).isTrue();
  }

  @Test
  public void testAsynchronousOk() {
    breaker = CircuitBreaker.create("test", vertx, new CircuitBreakerOptions());
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

    AtomicBoolean called = new AtomicBoolean();
    breaker.execute(future -> {
      vertx.setTimer(100, l -> {
        called.set(true);
        future.complete();
      });
    });

    await().until(called::get);
  }

  @Test
  public void testOpenAndCloseHandler() {
    AtomicInteger spyOpen = new AtomicInteger();
    AtomicInteger spyClosed = new AtomicInteger();
    breaker = CircuitBreaker.create("name", vertx, new CircuitBreakerOptions().setResetTimeout(-1))
        .openHandler((v) -> spyOpen.incrementAndGet())
        .closeHandler((v) -> spyClosed.incrementAndGet());

    assertThat(spyOpen.get()).isEqualTo(0);
    assertThat(spyClosed.get()).isEqualTo(0);

    // First failure
    try {
      breaker.executeBlocking(v -> {
        throw new RuntimeException("oh no, but this is expected");
      });
    } catch (RuntimeException e) {
      // Ignore it
    }

    assertThat(spyOpen.get()).isEqualTo(0);
    assertThat(spyClosed.get()).isEqualTo(0);
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

    for (int i = 1; i < CircuitBreakerOptions.DEFAULT_MAX_FAILURES; i++) {
      try {
        breaker.executeBlocking(v -> {
          throw new RuntimeException("oh no, but this is expected");
        });
      } catch (RuntimeException e) {
        // Ignore it
      }
    }
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
    assertThat(spyOpen.get()).isEqualTo(1);

    breaker.reset();
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
    assertThat(spyOpen.get()).isEqualTo(1);
    assertThat(spyClosed.get()).isEqualTo(1);
  }

  @Test
  public void testExceptionOnSynchronousCode() {
    AtomicBoolean called = new AtomicBoolean(false);
    CircuitBreakerOptions options = new CircuitBreakerOptions().setResetTimeout(-1);
    breaker = CircuitBreaker.create("test", vertx, options)
        .fallbackHandler(v -> {
          called.set(true);
        });
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

    for (int i = 0; i < options.getMaxFailures(); i++) {
      try {
        breaker.executeBlocking(v -> {
          throw new RuntimeException("oh no, but this is expected");
        });
      } catch (RuntimeException e) {
        // Ignore it
      }
    }
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
    assertThat(called.get()).isEqualTo(false);

    AtomicBoolean spy = new AtomicBoolean();
    breaker.executeBlocking(v -> {
      spy.set(true);
    });
    assertThat(spy.get()).isEqualTo(false);
    assertThat(called.get()).isEqualTo(true);
  }

  @Test
  public void testExceptionOnAsynchronousCode() {
    AtomicBoolean called = new AtomicBoolean(false);
    CircuitBreakerOptions options = new CircuitBreakerOptions().setResetTimeout(-1);
    breaker = CircuitBreaker.create("test", vertx, options)
        .fallbackHandler(v -> {
          called.set(true);
        });
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

    for (int i = 0; i < options.getMaxFailures(); i++) {
      try {
        breaker.execute(future -> {
          throw new RuntimeException("oh no, but this is expected");
        });
      } catch (RuntimeException e) {
        // Ignore it
      }
    }
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
    assertThat(called.get()).isEqualTo(false);

    AtomicBoolean spy = new AtomicBoolean();
    breaker.executeBlocking(v -> {
      spy.set(true);
    });
    assertThat(spy.get()).isEqualTo(false);
    assertThat(called.get()).isEqualTo(true);
  }

  @Test
  public void testFailureOnAsynchronousCode() {
    AtomicBoolean called = new AtomicBoolean(false);
    CircuitBreakerOptions options = new CircuitBreakerOptions().setResetTimeout(-1);
    breaker = CircuitBreaker.create("test", vertx, options)
        .fallbackHandler(v -> {
          called.set(true);
        });
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

    for (int i = 0; i < options.getMaxFailures(); i++) {
      breaker.execute(future -> {
        vertx.setTimer(100, l -> {
          future.fail("expected failure");
        });
      });
    }
    await().until(() -> breaker.state() == CircuitBreakerState.OPEN);
    assertThat(called.get()).isEqualTo(false);

    AtomicBoolean spy = new AtomicBoolean();
    breaker.execute(future -> {
      vertx.setTimer(100, l -> {
        future.fail("expected failure");
        spy.set(true);
      });
    });
    await().untilAtomic(called, is(true));
    assertThat(spy.get()).isEqualTo(false);
  }

  @Test
  public void testResetAttemptOnSynchronousCode() {
    AtomicBoolean called = new AtomicBoolean(false);
    CircuitBreakerOptions options = new CircuitBreakerOptions().setResetTimeout(100);
    breaker = CircuitBreaker.create("test", vertx, options)
        .fallbackHandler(v -> {
          called.set(true);
        });
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

    for (int i = 0; i < options.getMaxFailures(); i++) {
      try {
        breaker.executeBlocking(v -> {
          throw new RuntimeException("oh no, but this is expected");
        });
      } catch (RuntimeException e) {
        // Ignore it
      }
    }
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
    assertThat(called.get()).isEqualTo(false);

    await().until(() -> breaker.state() == CircuitBreakerState.HALF_OPEN);

    AtomicBoolean spy = new AtomicBoolean();
    breaker.executeBlocking(v -> {
      spy.set(true);
    });
    assertThat(spy.get()).isEqualTo(true);
    assertThat(called.get()).isEqualTo(false);
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
  }

  @Test
  public void testResetAttemptOnAsynchronousCode() {
    AtomicBoolean called = new AtomicBoolean(false);
    CircuitBreakerOptions options = new CircuitBreakerOptions().setResetTimeout(200);
    breaker = CircuitBreaker.create("test", vertx, options)
        .fallbackHandler(v -> {
          called.set(true);
        });
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

    for (int i = 0; i < options.getMaxFailures(); i++) {
      breaker.execute(future -> {
        vertx.setTimer(100, l -> {
          future.fail("expected failure");
        });
      });
    }
    await().until(() -> breaker.state() == CircuitBreakerState.OPEN);
    assertThat(called.get()).isEqualTo(false);

    await().until(() -> breaker.state() == CircuitBreakerState.HALF_OPEN);

    AtomicBoolean spy = new AtomicBoolean();
    breaker.execute(future -> {
      vertx.setTimer(100, l -> {
        future.complete();
        spy.set(true);
      });
    });
    await().untilAtomic(spy, is(true));
    assertThat(called.get()).isEqualTo(false);
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
  }

  @Test
  public void testResetAttemptThatFailsOnSynchronousCode() {
    AtomicBoolean called = new AtomicBoolean(false);
    CircuitBreakerOptions options = new CircuitBreakerOptions()
        .setResetTimeout(100)
        .setFallbackOnFailure(true);
    breaker = CircuitBreaker.create("test", vertx, options)
        .fallbackHandler(v -> {
          called.set(true);
        });
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

    for (int i = 0; i < options.getMaxFailures(); i++) {
      try {
        breaker.executeBlocking(v -> {
          throw new RuntimeException("oh no, but this is expected");
        });
      } catch (RuntimeException e) {
        // Ignore it
      }
    }
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
    assertThat(called.get()).isEqualTo(true);

    await().until(() -> breaker.state() == CircuitBreakerState.HALF_OPEN);
    called.set(false);

    try {
      breaker.executeBlocking(v -> {
        throw new RuntimeException("oh no, but this is expected");
      });
    } catch (RuntimeException e) {
      // Ignore it
    }
    assertThat(called.get()).isEqualTo(true);
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
  }

  @Test
  public void testResetAttemptThatFailsOnAsynchronousCode() {
    AtomicBoolean called = new AtomicBoolean(false);
    CircuitBreakerOptions options = new CircuitBreakerOptions()
        .setResetTimeout(100)
        .setFallbackOnFailure(true);
    breaker = CircuitBreaker.create("test", vertx, options)
        .fallbackHandler(v -> {
          called.set(true);
        });
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

    for (int i = 0; i < options.getMaxFailures(); i++) {
      breaker.execute(future -> {
        vertx.setTimer(100, l -> {
          future.fail("expected failure");
        });
      });
    }
    await().until(() -> breaker.state() == CircuitBreakerState.OPEN);
    assertThat(called.get()).isEqualTo(true);

    await().until(() -> breaker.state() == CircuitBreakerState.HALF_OPEN);
    called.set(false);

    breaker.execute(future -> {
      vertx.setTimer(10, l -> {
        future.fail("expected failure");
      });
    });
    await().untilAtomic(called, is(true));
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
  }


  @Test
  public void testTimeoutOnSynchronousCode() {
    AtomicBoolean called = new AtomicBoolean(false);
    CircuitBreakerOptions options = new CircuitBreakerOptions().setTimeout(100);
    breaker = CircuitBreaker.create("test", vertx, options)
        .fallbackHandler(v -> {
          called.set(true);
        });
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

    for (int i = 0; i < options.getMaxFailures(); i++) {
      breaker.executeBlocking(v -> {
        try {
          Thread.sleep(500);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    }

    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
    assertThat(called.get()).isEqualTo(false);

    AtomicBoolean spy = new AtomicBoolean();
    breaker.executeBlocking(v -> {
      spy.set(true);
    });
    assertThat(spy.get()).isEqualTo(false);
    assertThat(called.get()).isEqualTo(true);
  }

  @Test
  public void testTimeoutOnSynchronousCodeWithFallbackCalled() {
    AtomicBoolean called = new AtomicBoolean(false);
    CircuitBreakerOptions options = new CircuitBreakerOptions().setTimeout(100)
        .setFallbackOnFailure(true);
    breaker = CircuitBreaker.create("test", vertx, options)
        .fallbackHandler(v -> {
          called.set(true);
        });
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

    for (int i = 0; i < options.getMaxFailures(); i++) {
      breaker.executeBlocking(v -> {
        try {
          Thread.sleep(500);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    }

    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
    assertThat(called.get()).isEqualTo(true);

    AtomicBoolean spy = new AtomicBoolean();
    breaker.executeBlocking(v -> {
      spy.set(true);
    });
    assertThat(spy.get()).isEqualTo(false);
    assertThat(called.get()).isEqualTo(true);
  }

  @Test
  public void testTimeoutOnAsynchronousCode() {
    AtomicBoolean fallbackCalled = new AtomicBoolean(false);
    AtomicBoolean openCalled = new AtomicBoolean(false);
    CircuitBreakerOptions options = new CircuitBreakerOptions()
        .setTimeout(100)
        .setResetTimeout(-1);
    breaker = CircuitBreaker.create("test", vertx, options)
        .fallbackHandler(v -> {
          fallbackCalled.set(true);
        })
        .openHandler(v -> {
          openCalled.set(true);
        });
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

    for (int i = 0; i < options.getMaxFailures(); i++) {
      breaker.execute(future -> {
        // Do nothing with the future, this is a very bad thing.
      });
    }

    await().until(() -> breaker.state() == CircuitBreakerState.OPEN);
    assertThat(openCalled.get()).isEqualTo(true);
    assertThat(fallbackCalled.get()).isEqualTo(false);

    breaker.execute(future -> {
      // Do nothing with the future, this is a very bad thing.
    });
    // Immediate fallback
    assertThat(fallbackCalled.get()).isEqualTo(true);
  }

  @Test
  public void testResetAttemptOnTimeout() {
    AtomicBoolean called = new AtomicBoolean(false);
    AtomicBoolean hasBeenOpened = new AtomicBoolean(false);
    CircuitBreakerOptions options = new CircuitBreakerOptions()
        .setResetTimeout(100)
        .setTimeout(10)
        .setFallbackOnFailure(true);
    breaker = CircuitBreaker.create("test", vertx, options)
        .fallbackHandler(v -> {
          called.set(true);
        })
        .openHandler(v -> {
          hasBeenOpened.set(true);
        });
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

    for (int i = 0; i < options.getMaxFailures(); i++) {
      breaker.execute(future -> {
        // Do nothing with the future, this is a very bad thing.
      });
    }
    await().untilAtomic(hasBeenOpened, is(true));
    assertThat(called.get()).isEqualTo(true);

    await().until(() -> breaker.state() == CircuitBreakerState.HALF_OPEN);
    called.set(false);

    breaker.execute(Future::complete);
    await().until(() -> breaker.state() == CircuitBreakerState.CLOSED);
    await().untilAtomic(called, is(false));
  }

  @Test
  public void testResetAttemptThatFailsOnTimeout() {
    AtomicBoolean called = new AtomicBoolean(false);
    AtomicBoolean hasBeenOpened = new AtomicBoolean(false);
    CircuitBreakerOptions options = new CircuitBreakerOptions()
        .setResetTimeout(100)
        .setTimeout(10)
        .setFallbackOnFailure(true);
    breaker = CircuitBreaker.create("test", vertx, options)
        .fallbackHandler(v -> {
          called.set(true);
        })
        .openHandler(v -> {
          hasBeenOpened.set(true);
        });
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

    for (int i = 0; i < options.getMaxFailures(); i++) {
      breaker.execute(future -> {
        // Do nothing with the future, this is a very bad thing.
      });
    }
    await().untilAtomic(hasBeenOpened, is(true));
    assertThat(called.get()).isEqualTo(true);
    await().until(() -> breaker.state() == CircuitBreakerState.HALF_OPEN);
    hasBeenOpened.set(false);
    called.set(false);

    breaker.execute(future -> {
      // Do nothing with the future, this is a very bad thing.
    });
    // Failed again, open circuit
    await().until( () -> breaker.state() == CircuitBreakerState.OPEN);
    await().untilAtomic(called, is(true));
    await().untilAtomic(hasBeenOpened, is(true));

    hasBeenOpened.set(false);
    called.set(false);

    breaker.execute(future -> {
      // Do nothing with the future, this is a very bad thing.
    });
    // Failed again, open circuit
    await().until( () -> breaker.state() == CircuitBreakerState.OPEN);
    await().untilAtomic(called, is(true));
    await().untilAtomic(hasBeenOpened, is(true));

    hasBeenOpened.set(false);
    called.set(false);

    hasBeenOpened.set(false);
    called.set(false);

    for (int i = 0; i < options.getMaxFailures(); i++) {
      breaker.execute(Future::complete);
    }

    await().until( () -> breaker.state() == CircuitBreakerState.CLOSED);
    await().untilAtomic(called, is(false));
    await().untilAtomic(hasBeenOpened, is(false));


  }

  @Test
  public void testThatOnlyOneRequestIsCheckedInHalfOpen() {
    AtomicBoolean called = new AtomicBoolean(false);
    AtomicBoolean hasBeenOpened = new AtomicBoolean(false);
    CircuitBreakerOptions options = new CircuitBreakerOptions()
        .setResetTimeout(1000)
        .setFallbackOnFailure(true);
    breaker = CircuitBreaker.create("test", vertx, options)
        .fallbackHandler(v -> {
          called.set(true);
        })
        .openHandler(v -> {
          hasBeenOpened.set(true);
        });
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

    for (int i = 0; i < options.getMaxFailures(); i++) {
      breaker.execute(future -> {
        future.fail("expected failure");
      });
    }
    await().untilAtomic(hasBeenOpened, is(true));
    assertThat(called.get()).isEqualTo(true);

    await().until(() -> breaker.state() == CircuitBreakerState.HALF_OPEN);
    called.set(false);

    AtomicInteger fallbackCalled = new AtomicInteger();
    for (int i = 0; i < options.getMaxFailures(); i++) {
      breaker.executeWithFallback(future -> {
        vertx.setTimer(500, l -> {
          future.complete();
        });
      }, v -> {
        fallbackCalled.incrementAndGet();
      });
    }

    await().until(() -> breaker.state() == CircuitBreakerState.CLOSED);
    assertThat(fallbackCalled.get()).isEqualTo(options.getMaxFailures() - 1);

  }
}