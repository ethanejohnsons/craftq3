package dev.bluevista.craftq3.core.net;

/** Packet-local rank-based adaptive tree; independent of the pre-trained in-band codebook. */
final class AdaptiveHuffmanTree {
  private static final int UNSEEN = 256;
  private static final int ROOT_RANK = 512;
  private final Node[] ranks = new Node[ROOT_RANK + 1];
  private final Node[] symbols = new Node[256];
  private final Node root = new Node(UNSEEN, ROOT_RANK, null);
  private Node unseen = root;

  AdaptiveHuffmanTree() {
    ranks[ROOT_RANK] = root;
  }

  Node root() {
    return root;
  }

  Node unseen() {
    return unseen;
  }

  Node symbol(int symbol) {
    return symbols[symbol];
  }

  void observe(int symbol) {
    Node node = symbols[symbol];
    if (node == null) {
      Node branch = unseen;
      Node leaf = new Node(symbol, branch.rank - 1, branch);
      unseen = new Node(UNSEEN, branch.rank - 2, branch);
      leaf.weight = 1;
      branch.left = unseen;
      branch.right = leaf;
      branch.weight = 1;
      symbols[symbol] = leaf;
      ranks[leaf.rank] = leaf;
      ranks[unseen.rank] = unseen;
      node = branch.parent;
    }
    while (node != null) {
      // Equal-weight ranks are contiguous; the block's final rank is its swap candidate.
      Node leader = node;
      for (int rank = node.rank + 1; rank <= ROOT_RANK; rank++) {
        Node candidate = ranks[rank];
        if (candidate.weight != node.weight) break;
        leader = candidate;
      }
      if (leader != node && leader != node.parent) swap(node, leader);
      node.weight++;
      node = node.parent;
    }
  }

  private void swap(Node first, Node second) {
    Node firstParent = first.parent;
    Node secondParent = second.parent;
    boolean firstLeft = firstParent.left == first;
    boolean secondLeft = secondParent.left == second;
    if (firstLeft) firstParent.left = second;
    else firstParent.right = second;
    if (secondLeft) secondParent.left = first;
    else secondParent.right = first;
    first.parent = secondParent;
    second.parent = firstParent;
    int rank = first.rank;
    first.rank = second.rank;
    second.rank = rank;
    ranks[first.rank] = first;
    ranks[second.rank] = second;
  }

  static final class Node {
    final int symbol;
    int rank;
    int weight;
    Node parent;
    Node left;
    Node right;

    Node(int symbol, int rank, Node parent) {
      this.symbol = symbol;
      this.rank = rank;
      this.parent = parent;
    }
  }
}
