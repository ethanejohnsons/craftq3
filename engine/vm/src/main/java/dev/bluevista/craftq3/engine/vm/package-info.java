/**
 * The QVM runtime: {@link dev.bluevista.craftq3.engine.vm.QvmImage} (parsed, validated, immutable
 * code shared across instances), {@link dev.bluevista.craftq3.engine.vm.QvmInstance} (data segment
 * and interpreter state, one owner thread), {@link dev.bluevista.craftq3.engine.vm.QvmMemory}
 * (checked guest memory access) and the {@link dev.bluevista.craftq3.engine.vm.Syscalls} boundary.
 * Import tables and struct layouts live in {@code engine:vm-abi}.
 */
package dev.bluevista.craftq3.engine.vm;
