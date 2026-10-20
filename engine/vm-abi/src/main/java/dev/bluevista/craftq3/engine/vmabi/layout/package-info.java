/**
 * Guest struct layouts: one class per Quake III struct with named byte offsets, per {@link
 * dev.bluevista.craftq3.engine.vmabi.AbiProfile} where the profiles differ, and read/write helpers
 * over {@link dev.bluevista.craftq3.engine.vm.QvmMemory}. Hosts use these instead of raw offsets.
 */
package dev.bluevista.craftq3.engine.vmabi.layout;
