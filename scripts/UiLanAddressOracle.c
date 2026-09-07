/* Authored initialization of the declared native address-parser enablement cvar.
   The unchanged network unit is never initialized and no socket is opened. */
#include "qcommon/net_ip.c"
void UiLanPrepareAddressParser(void) {
  static cvar_t enabled = {.integer = NET_ENABLEV4 | NET_ENABLEV6};
  net_enabled = &enabled;
}
