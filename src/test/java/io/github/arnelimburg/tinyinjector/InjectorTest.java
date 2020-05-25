/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.arnelimburg.tinyinjector;

import javax.enterprise.inject.literal.NamedLiteral;
import javax.enterprise.util.AnnotationLiteral;

import org.atinject.tck.Tck;
import org.atinject.tck.auto.Car;
import org.atinject.tck.auto.Convertible;
import org.atinject.tck.auto.Drivers;
import org.atinject.tck.auto.DriversSeat;
import org.atinject.tck.auto.Engine;
import org.atinject.tck.auto.Seat;
import org.atinject.tck.auto.Tire;
import org.atinject.tck.auto.V8Engine;
import org.atinject.tck.auto.accessories.SpareTire;

import junit.framework.Test;

public class InjectorTest {

  public static Test suite() {
    Car car = new Injector()
      .injecting(Convertible.class).into(Car.class)
      .injecting(DriversSeat.class).into(Seat.class, new AnnotationLiteral<Drivers>() { })
      .injecting(V8Engine.class).into(Engine.class)
      .injecting(SpareTire.class).into(Tire.class, NamedLiteral.of("spare"))
      .getInstance(Car.class);
    return Tck.testsFor(car, true, true);
  }
}
