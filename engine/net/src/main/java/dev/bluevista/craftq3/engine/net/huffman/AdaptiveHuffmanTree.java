package dev.bluevista.craftq3.engine.net.huffman;

/**
 * Packet-local rank-based adaptive Huffman tree used by the compressed {@code connect} datagram;
 * independent of the fixed {@link HuffmanCodebook}. One tree codes one datagram.
 */
public final class AdaptiveHuffmanTree {
  private static final int UNSEEN = 256;
  private static final int ROOT_RANK = 512;
  private final Node[] ranks = new Node[ROOT_RANK + 1];
  private final Node[] symbols = new Node[256];
  private final Node root = new Node(UNSEEN, ROOT_RANK, null);
  private Node unseen = root;

  public AdaptiveHuffmanTree() {
    ranks[ROOT_RANK] = root;
  }

  public Node root() {
    return root;
  }

  public Node unseen() {
    return unseen;
  }

  /** The leaf of a symbol already observed, or null. */
  public Node symbol(int symbol) {
    return symbols[symbol];
  }

  /** Adds one occurrence of a byte symbol, restructuring the tree. */
  public void observe(int symbol) {
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

  /** A tree node; internal nodes have both children, leaves have none. */
  public static final class Node {
    private final int symbol;
    private int rank;
    private int weight;
    private Node parent;
    private Node left;
    private Node right;

    Node(int symbol, int rank, Node parent) {
      this.symbol = symbol;
      this.rank = rank;
      this.parent = parent;
    }

    /** The byte symbol of a leaf, or 256 for the unseen-symbol leaf and internal nodes. */
    public int symbol() {
      return symbol;
    }

    public Node parent() {
      return parent;
    }

    public Node left() {
      return left;
    }

    public Node right() {
      return right;
    }

    public boolean isLeaf() {
      return left == null;
    }
  }
}
