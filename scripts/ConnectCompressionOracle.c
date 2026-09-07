/* Authored capture-only adaptive-Huffman observer. No socket or native routine copy. */
#define main unused_connectionless_main
#define Q_strncpyz Oracle_Q_strncpyz
#define ShortSwap Oracle_ShortSwap
#define va Oracle_va
#include "NetchanOracle.c"
#undef main
#undef Q_strncpyz
#undef ShortSwap
#undef va

int main(void) {
    Netchan_Init(12345);
    char operation[16], encoded[MAX_MSGLEN * 2 + 2];
    byte input[131072];
    while (scanf("%15s", operation) == 1) {
        int offset = 0, capacity = MAX_MSGLEN;
        if (!strcmp(operation, "compress") || !strcmp(operation, "decompress")) {
            if (scanf("%d %d %32769s", &offset, &capacity, encoded) != 3) return 2;
        } else if (scanf("%32769s", encoded) != 1) return 2;
        memset(input, 0, sizeof(input));
        int length = decode(encoded, input);
        if (offset < 0 || offset > length || capacity < 1 || capacity > MAX_MSGLEN) return 2;
        if (!strcmp(operation, "compress") || !strcmp(operation, "decompress")) {
            msg_t message;
            MSG_InitOOB(&message, input, capacity);
            message.cursize = length;
            if (!strcmp(operation, "compress")) Huff_Compress(&message, offset);
            else Huff_Decompress(&message, offset);
            printf("result %d %d %d ", message.cursize, message.readcount, message.bit);
            if (message.cursize < 0 || message.cursize > (int)sizeof(input)) return 3;
            hex(input, message.cursize);
            printf(" cursor %d\n", Huff_getBloc());
        } else if (!strcmp(operation, "data")) {
            netadr_t address = {0}; address.type = NA_IP;
            NET_OutOfBandData(NS_CLIENT, address, input, length);
        } else if (!strcmp(operation, "codes")) {
            huffman_t huff;
            Huff_Init(&huff);
            for (int i = 0; i < length; i++) Huff_addRef(&huff.compressor, input[i]);
            fputs("codes", stdout);
            for (int i = 0; i <= HMAX; i++) {
                node_t *node = huff.compressor.loc[i];
                if (!node) continue;
                char bits[768]; int count = 0;
                for (node_t *cursor = node; cursor->parent; cursor = cursor->parent)
                    bits[count++] = cursor->parent->right == cursor ? '1' : '0';
                printf(" %d:%d:", i, node->weight);
                while (count) putchar(bits[--count]);
            }
            putchar('\n');
        } else return 2;
        fflush(stdout);
    }
    return 0;
}
