/*
 * Copyright 2019 Digital Asset (Switzerland) GmbH and/or its affiliates
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.digitalasset.testing.store

import com.digitalasset.ledger.api.v1.value.Value

trait ValueStore {
  def put(key: String, value: Value): Unit
  def get(key: String): Value
  def remove(key: String): Unit
}
