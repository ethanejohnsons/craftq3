package dev.bluevista.craftq3.engine.vmabi;

/**
 * Imports that exist only in the retail ({@link AbiProfile#RETAIL_1999}) numbering. Everything else
 * the retail builds call maps onto a 1.32 entry through {@link ImportTable}.
 */
public enum RetailImport implements VmImport {
  /** Pre-1.27 AAS visibility query in slot 300 (replaced by AAS_ENABLE_ROUTING_AREA). */
  RETAIL_BOTLIB_AAS_ENTITY_VISIBLE(
      VmModule.QAGAME, 300, VmImport.UNKNOWN_ARGS, RetailImport.obsoleteVisibility()),
  /** Pre-1.27 AAS field-of-vision query in slot 301 (replaced by AAS_BBOX_AREAS). */
  RETAIL_BOTLIB_AAS_IN_FIELD_OF_VISION(
      VmModule.QAGAME, 301, VmImport.UNKNOWN_ARGS, RetailImport.obsoleteVisibility()),
  /** Pre-1.27 AAS visible-clients query in slot 302 (replaced by AAS_AREA_INFO). */
  RETAIL_BOTLIB_AAS_VISIBLE_CLIENTS(
      VmModule.QAGAME, 302, VmImport.UNKNOWN_ARGS, RetailImport.obsoleteVisibility()),
  RETAIL_BOTLIB_EA_USE_ITEM(VmModule.QAGAME, 402, 2, RetailImport.inventoryAction()),
  RETAIL_BOTLIB_EA_DROP_ITEM(VmModule.QAGAME, 403, 2, RetailImport.inventoryAction()),
  RETAIL_BOTLIB_EA_USE_INV(VmModule.QAGAME, 404, 2, RetailImport.inventoryAction()),
  RETAIL_BOTLIB_EA_DROP_INV(VmModule.QAGAME, 405, 2, RetailImport.inventoryAction()),

  /** Retail server browser: number of local (LAN) servers. */
  RETAIL_UI_LAN_GETLOCALSERVERCOUNT(VmModule.UI, 46, 0, Policy.HOST),
  /** Retail server browser: address of local server {@code n} into a buffer. */
  RETAIL_UI_LAN_GETLOCALSERVERADDRESSSTRING(VmModule.UI, 47, 3, Policy.host(0).clearingString(1)),
  /** Retail server browser: number of servers from the master server. */
  RETAIL_UI_LAN_GETGLOBALSERVERCOUNT(VmModule.UI, 48, 0, Policy.HOST),
  /** Retail server browser: address of global server {@code n} into a buffer. */
  RETAIL_UI_LAN_GETGLOBALSERVERADDRESSSTRING(VmModule.UI, 49, 3, Policy.host(0).clearingString(1));

  private final VmModule module;
  private final int retailNumber;
  private final int argCount;
  private final Policy policy;

  RetailImport(VmModule module, int retailNumber, int argCount, Policy policy) {
    this.module = module;
    this.retailNumber = retailNumber;
    this.argCount = argCount;
    this.policy = policy;
  }

  private static Policy obsoleteVisibility() {
    return Policy.stub(
        0, "obsolete pre-1.27 visibility query that no shipped build calls; 0 means not visible");
  }

  private static Policy inventoryAction() {
    return Policy.stub(
        0, "pre-1.27 inventory action; Quake III has no usable inventory, so it is ignored");
  }

  @Override
  public VmModule module() {
    return module;
  }

  /** Not part of the 1.32 interface. */
  @Override
  public int number() {
    return -1;
  }

  /** The trap number in the retail numbering. */
  public int retailNumber() {
    return retailNumber;
  }

  @Override
  public int argCount() {
    return argCount;
  }

  @Override
  public Policy policy() {
    return policy;
  }
}
