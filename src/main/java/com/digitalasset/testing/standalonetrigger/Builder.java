/*
 * Copyright 2019 Digital Asset (Switzerland) GmbH and/or its affiliates
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.digitalasset.testing.standalonetrigger;

import com.daml.ledger.javaapi.data.Party;
import java.nio.file.Path;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class Builder {
  private String darPath;
  private String triggerName;
  private String ledgerHost = "localhost";
  private Supplier<String> ledgerPort = () -> "6865";
  private String party;
  private String timeMode = "--static-time";

  public Builder dar(Path path) {
    this.darPath = path.toString();
    return this;
  }

  public Builder triggerName(String triggerName) {
    this.triggerName = triggerName;
    return this;
  }

  public Builder ledgerHost(String ledgerHost) {
    this.ledgerHost = ledgerHost;
    return this;
  }

  public Builder ledgerPort(int ledgerPort) {
    return ledgerPort(() -> ledgerPort);
  }

  public Builder ledgerPort(IntSupplier ledgerPort) {
    this.ledgerPort = () -> String.valueOf(ledgerPort.getAsInt());
    return this;
  }

  public Builder useWallClockTime() {
    this.timeMode = "--wall-clock-time";
    return this;
  }

  public Builder party(Party party) {
    this.party = party.getValue();
    return this;
  }

  public StandaloneTrigger build() {
    return new StandaloneTrigger(darPath, triggerName, ledgerHost, ledgerPort, party, timeMode);
  }
}
