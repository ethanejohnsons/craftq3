package dev.bluevista.craftq3.engine.testkit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test that needs user-owned Quake III data. The test runs only when {@link
 * RealDataRoot#ENVIRONMENT_VARIABLE} names a directory containing {@code baseq3}; otherwise it is
 * skipped. Real data is never committed and never required by CI.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RealDataRoot.Condition.class)
public @interface RealData {}
