package com.alibaba.middleware.race.store;

import com.alibaba.middleware.race.util.PrintUtil;
import com.alibaba.middleware.race.util.TypeUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by liuzhendong on 16/7/14.
 * 为简化实现,定有以下约定
 * 约定1:key与data的长度固定
 * 约定2:每个node只用一个page保存
 * 约定3:内节点全部加载到内存中,叶节点只保存指针,具体的内容存在叶节点map中,并实行淘汰策略
 * 约定4:内节点中的关键字不重复,叶节点中的关键字可以少量重复
 */
public class BTree {

    public static final int NODE_RESERVE_LEN = 20;
    public static class BNode{
        public byte keySize;//不能超过255
        public byte dataSize;//数据大小
        public boolean childIsLeaf;
        public List<byte[]> keyvalues;
        public List<Object>  childs;
        public Page page;
        public BNode parent; //父节点
    }
    //节点分裂模式
    public enum SplitMode{
        LEFT,MID,RIGHT;
    }
    public boolean debug = false;
    //内存中的
    private AtomicLong leafIndex  = new AtomicLong(System.currentTimeMillis());
    public PageMgr pageMgr; //磁盘页管理
    public BNode root; //根节点

    private final int maxLeafNode = StoreConfig.MAX_LEAF_IN_MEMORY_PER_BTREE;
    //B树节点,尤其是叶子节点,大部分都放在磁盘中, 这里保存映射关系,如果不停地刷新磁盘,则效率会比较低
    public Map<Long,BNode>  leafCache = new LinkedHashMap<Long, BNode>(){
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long,BNode> eldest) {
            //如果存储在内存中,则不淘汰
            if(pageMgr == null) return false;
            if(super.size() > maxLeafNode){
                BNode leaf = (BNode)eldest.getValue();
                try {
                    flushBnodeIntoDisk(leaf);
                    //help gc
                    if(leaf.childs != null){
                        leaf.childs.clear();
                        leaf.childs = null;
                    }
                    if(leaf.keyvalues != null){
                        leaf.keyvalues.clear();
                        leaf.keyvalues = null;
                    }
                }catch (IOException e){
                    e.printStackTrace();
                }
                return true;
            }
            return false;
        }

        @Override
        public BNode get(Object key){
            if(super.containsKey(key)){
                return super.get(key);
            }
            try {
                BNode leaf = loadBnodeFromDisk((Long) key);
                super.put((Long) key,leaf);
                return leaf;
            }catch (IOException e){
                e.printStackTrace();
            }
            return null;
        }

    };


    //默认建的树直接存储在内存中
    public BTree(){
    }
    public static BTree createBTreeWithDisk(String filePath)throws IOException {
        BTree bTree = new BTree();
        bTree.pageMgr = PageMgr.createNew(filePath, StoreConfig.DEFALT_BTREE_DISK_COST);
        return bTree;
    }

    public static BTree loadBTreeFromDisk(String filePath)throws IOException {
        //磁盘中加载btree
        BTree bTree = new BTree();
        bTree.pageMgr = PageMgr.load(filePath);
        bTree.root = bTree.loadBnodeFromDisk(bTree.pageMgr.firstAllocatedPos);
        ArrayDeque<BNode> nodeStack = new ArrayDeque<BNode>();
        nodeStack.push(bTree.root);
        while (nodeStack.size() > 0){
            BNode currNode = nodeStack.pop();
            if(!currNode.childIsLeaf){
                for (int i = 0; i < currNode.childs.size(); i++) {
                    BNode tmp = bTree.loadBnodeFromDisk((Long) currNode.childs.get(i));
                    tmp.parent = currNode;
                    if(!tmp.childIsLeaf) nodeStack.push(tmp);
                }
            }
        }
        return bTree;
    }

    private BNode createInnerBnode(byte keySize, List<byte[]> keyvalues, List<Object> childs)throws IOException{
        BNode bNode = new BNode();
        bNode.keySize = keySize;
        bNode.dataSize = 0;
        if(pageMgr != null){
            bNode.page = pageMgr.allocateNewPage();
        }else {
            bNode.page = new Page().startIndex(leafIndex.addAndGet(1));
        }
        if(keyvalues != null){
            bNode.keyvalues = keyvalues;
        }else {
            bNode.keyvalues = new ArrayList<byte[]>(bNode.page.getPageSize()/(bNode.keySize + 8) - 4);
        }
        if(childs != null){
            bNode.childs = childs;
        }else {
            bNode.childs = new ArrayList<Object>(bNode.keyvalues.size() + 1);
        }
        return bNode;
    }

    private BNode createLeafNode(byte keySize,byte dataSize,List<byte[]> keyvalues)throws IOException{
        BNode bNode = new BNode();
        bNode.keySize = keySize;
        bNode.dataSize = dataSize;
        if(pageMgr != null){
            bNode.page = pageMgr.allocateNewPage();
        }else {
            bNode.page = new Page().startIndex(leafIndex.addAndGet(1));
        }
        if(keyvalues == null){
            bNode.keyvalues = new ArrayList<byte[]>(bNode.page.getPageSize()/(bNode.keySize + bNode.dataSize) - 4);
        }else {
            bNode.keyvalues = keyvalues;
        }
        return bNode;
    }


    //根据单key查找,不允许查找前缀 TODO 支持前缀
    public List<byte[]> query(byte[] key)throws IOException{
        //单key查找
        if(root == null) return new ArrayList<byte[]>(4);
        if(key.length != root.keySize){
            throw new RuntimeException(String.format("查询的key与索引key的长度不一致 %d != %d",key.length, root.keySize));
        }
        BNode leaf = queryLeafBnode(key);

        return getResultByKey(leaf, key);
    }

    public List<byte[]> getResultByKey(BNode leaf, byte[] key){
        int index = findChildIndex(leaf.keyvalues, key, key.length);
        if(index >= leaf.keyvalues.size()){
            return new ArrayList<byte[]>(4);
        }
        print("getResultByKey originSize:%d index:%d",leaf.keyvalues.size(),index);
        List<byte[]> result = new ArrayList<byte[]>();
        //考虑到key会重复,所以往前往后找
        for (int i = index; i < leaf.keyvalues.size()-1; i++) {
            if(compare(leaf.keyvalues.get(i),key,key.length) == 0){
                result.add(leaf.keyvalues.get(i));
            }else {
                break;
            }
        }
        for (int i = index-1; i >= 0; i--) {
            if(compare(leaf.keyvalues.get(i),key,key.length) == 0){
                result.add(leaf.keyvalues.get(i));
            }else {
                break;
            }
        }
        return result;
    }


    //注意 这里给的是视图
    public List<byte[]> getResultByRange(BNode leaf, byte[] from, byte[] to){
        int start = 0, end = leaf.keyvalues.size()-1;
        if(from != null){
            start = findChildIndex(leaf.keyvalues,from,from.length);
            //start校验
            if(start == leaf.keyvalues.size()){
                return new ArrayList<byte[]>(4);
            }
            if(compare(from,leaf.keyvalues.get(start),from.length) == 0){
                start--;
                while (start >= 0){
                    if(compare(from,leaf.keyvalues.get(start),from.length) == 0){
                        start--;
                    }else {
                        break;
                    }
                }
                start++;
            }
        }

        if(to != null){
            end = findChildIndex(leaf.keyvalues,to ,to.length);
            if(end == leaf.keyvalues.size()){
                end--;
            }else if(compare(to, leaf.keyvalues.get(end),to.length) == 0){
                end--;
                while (end >= 0){
                    if(compare(to, leaf.keyvalues.get(end),to.length) == 0){
                        end--;
                    }else {
                        break;
                    }
                }
            }else {
                end --;
            }
        }

        if(start > end){
            return new ArrayList<byte[]>(4);
        }
        return leaf.keyvalues.subList(start,end+1);
    }



    //范围查找,不支持前缀,注意,如果查找的内容太多,则会导致内存崩溃,这真是一个大bug
    public List<byte[]> query(byte[] from, byte[] to)throws IOException{
        //用队列保存,注意首尾项的筛选即可
        if(root == null) return  new ArrayList<byte[]>();
        if(from.length != root.keySize || from.length != to.length){
            throw new RuntimeException(String.format("查询的key长度不一致 %d != %d != %d",from.length, to.length, root.keySize));
        }
        //如果根节点就是叶子节点
        if(root.childs == null) return getResultByRange(root, from, to);

        return _queryByRange(root,from,to);
    }

    private List<byte[]> _queryByRange(Object bNode,byte[] from,byte[] to){
        Object left = bNode, right= bNode;
        ArrayDeque<Object> mids = new ArrayDeque<Object>();
        while (left == right){
            if(left instanceof Long){
                //已经是叶子节点
                return getResultByRange(leafCache.get(left),from,to);
            }
            BNode currNode = (BNode)left;
            int start = 0,end = currNode.keyvalues.size();
            if(from != null){
                start = findChildIndex(currNode.keyvalues,from,from.length);
            }
            if(to != null){
                end = findChildIndex(currNode.keyvalues, to, to.length);
            }
            if (start == end){
                left = right = currNode.childs.get(start);
                continue;
            }
            left  = currNode.childs.get(start);
            right = currNode.childs.get(end);
            for (int i = start + 1; i < end; i++) {
                mids.push(currNode.childs.get(i));
            }
        }

        //查出来要保证是有序的
        List<byte[]> leftRes = _queryByRange(left,from,null);
        List<byte[]> rightRes = _queryByRange(right,null,to);
        List<byte[]> allRes = new ArrayList<byte[]>();
        allRes.addAll(leftRes);
        while (mids.size() > 0){
            Object curr = mids.pop();
            if(curr instanceof  BNode){
                //添加
                mids.addAll(((BNode)curr).childs);
            }else {
                allRes.addAll(leafCache.get(curr).keyvalues);
            }
        }
        allRes.addAll(rightRes);
        return allRes;
    }


    //在查询的时候,如果发现是指针,则从磁盘中加载
    public BNode queryLeafBnode(byte[] key)throws IOException{
        return queryLeafBnode(key,(byte) key.length);
    }
    public BNode queryLeafBnode(byte[] key,byte keyLen)throws IOException{
        //print("queryLeafBnode:%d", key.length);
        if(root.childs == null) return root; //说明当前没有任何分裂
        BNode currNode = root;
        if(root.keySize < keyLen){
            throw new RuntimeException(String.format("查询的key长度不能大于索引key的长度 %d > %d", keyLen, root.keySize));
        }
        while (true){
            int keySize = currNode.keySize > keyLen ? keyLen : currNode.keySize;
            int index = findChildIndex(currNode.keyvalues, key, keySize);
            Object child = currNode.childs.get(index);
            if(child instanceof BNode){
                currNode = (BNode)child;
                continue;
            }
            BNode leaf = leafCache.get(child);
            if(leaf != null){
                leaf.parent = currNode;
            }
            return leaf;
        }
    }

    //插入一条数据
    public void insert(byte[] key,byte[] data)throws IOException{
        byte[] keyvalue = new byte[key.length+data.length];
        System.arraycopy(key,0,keyvalue,0,key.length);
        System.arraycopy(data,0,keyvalue,key.length,data.length);
        if(root == null){
            root = createLeafNode((byte)(key.length),(byte)data.length,null);
            root.keyvalues.add(keyvalue);
            return;
        }
        BNode leaf = queryLeafBnode(key);
        addAndSplit(leaf,keyvalue,key.length);
    }


    //插入一条数据
    public void insert(byte[] keyvalue, byte keySize)throws IOException{
        if(root == null){
            root = createLeafNode(keySize,(byte) (keyvalue.length - keySize),null);
            root.keyvalues.add(keyvalue);
            return;
        }
        BNode leaf = queryLeafBnode(keyvalue,keySize);
        addAndSplit(leaf,keyvalue,keySize);
    }

    private void addAndSplit(BNode leaf, byte[] keyvalue,int keySize)throws IOException{
        if(leaf.keySize != keySize){
            throw new RuntimeException(String.format("插入的数据keysize不一致 %d != %d",leaf.keySize, keySize));
        }
        int index = findChildIndex(leaf.keyvalues,keyvalue,leaf.keySize);
        if(index >= leaf.keyvalues.size()){
            leaf.keyvalues.add(keyvalue);
        }else {
            leaf.keyvalues.add(index,keyvalue);
        }
        splitLeaf(leaf);
    }

    private void splitLeaf(BNode leaf)throws IOException{
        if(leaf.keyvalues.size() * (leaf.keySize+leaf.dataSize) < leaf.page.getPageSize() - NODE_RESERVE_LEN){
            //不需要分裂
            return;
        }
        //print("splitLeaf keyNum:%d",leaf.keyvalues.size());
        int mid = leaf.keyvalues.size()/2;
        byte[] key = Arrays.copyOfRange(leaf.keyvalues.get(mid),0,leaf.keySize);
        List<byte[]> split1 = new ArrayList<byte[]>(1024);
        List<byte[]> split2 = new ArrayList<byte[]>(1024);
        split1.addAll(leaf.keyvalues.subList(0,mid+1));
        split2.addAll(leaf.keyvalues.subList(mid+1,leaf.keyvalues.size()));
        leaf.keyvalues = split1;

        BNode other = createLeafNode(leaf.keySize,leaf.dataSize,split2);

        //当前是根节点
        if(leaf.parent == null){
            BNode parent = createInnerBnode((byte) key.length,null,null);
            parent.keyvalues.add(key);
            parent.childs.add(leaf.page.getStartIndex());
            parent.childs.add(other.page.getStartIndex());
            leaf.parent = parent;
            other.parent = parent;
            leafCache.put(leaf.page.getStartIndex(), leaf);
            leafCache.put(other.page.getStartIndex(), other);
            root = parent;
        }else {
            BNode parent = leaf.parent;
            int index = findChildIndex(parent.keyvalues,key,key.length);
            parent.keyvalues.add(index,key);
            parent.childs.add(index+1,other.page.getStartIndex());
            other.parent = parent;
            leafCache.put(other.page.getStartIndex(), other);
            //递归分裂父节点
            splitInnerNode(parent);
        }
    }

    private void splitInnerNode(BNode inNode)throws IOException{
        if(inNode.keyvalues.size() * (inNode.keySize + 8) < inNode.page.getPageSize() - NODE_RESERVE_LEN){
            //不需要分裂
            return;
        }
        print("splitInner keyNum:%d", inNode.keyvalues.size());

        int mid = inNode.keyvalues.size()/2;
        byte[] key = inNode.keyvalues.get(mid);

        //切分key,中间的key去掉
        List<byte[]> keysplit1 = new ArrayList<byte[]>(1024);
        List<byte[]> keysplit2 = new ArrayList<byte[]>(1024);
        keysplit1.addAll(inNode.keyvalues.subList(0,mid));
        keysplit2.addAll(inNode.keyvalues.subList(mid+1,inNode.keyvalues.size()));

        List<Object> childSplit1 = new ArrayList<Object>(1024 + 1);
        List<Object> childSplit2 = new ArrayList<Object>(1024 + 1);
        childSplit1.addAll(inNode.childs.subList(0,mid+1));
        childSplit2.addAll(inNode.childs.subList(mid+1,inNode.childs.size()));
        inNode.keyvalues = keysplit1;
        inNode.childs = childSplit1;

        BNode other = createInnerBnode(inNode.keySize, keysplit2, childSplit2);
        //当前是根节点
        if(inNode.parent == null){
            BNode parent = createInnerBnode(inNode.keySize,null,null);
            parent.keyvalues.add(key);
            parent.childs.add(inNode);
            parent.childs.add(other);
            inNode.parent = parent;
            other.parent = parent;
            root = parent;
        }else {
            BNode parent = inNode.parent;
            int index = findChildIndex(parent.keyvalues,key,key.length);
            parent.keyvalues.add(index,key);
            parent.childs.add(index+1,other);
            other.parent = parent;
            //递归分裂父节点
            splitInnerNode(parent);
        }
    }



    //磁盘交互部分
    private BNode loadBnodeFromDisk(Long pointer)throws IOException{
        if(pageMgr == null) return null;
        Page page = pageMgr.getPageByPos(pointer);
        byte[] body = page.readFromDisk();
        int curr = 0;
        int keyNum = (int) TypeUtil.bytesToLong(Arrays.copyOfRange(body, curr, 2));
        curr += 2;
        byte[] childNumBytes = Arrays.copyOfRange(body, curr, curr+2);
        boolean childIsLeaf = (childNumBytes[0] & 0x80) != 0;
        childNumBytes[0] = (byte) (childNumBytes[0] & 0x7F);
        int childNum = (int) TypeUtil.bytesToLong(childNumBytes);
        curr += 2;
        byte keySize = body[curr++];
        byte dataSize = body[curr++];
        List<byte[]> keyvalues = new ArrayList<byte[]>(keyNum+10);
        for(int i =0; i < keyNum;i++){
            keyvalues.add(Arrays.copyOfRange(body,curr, curr + keySize+dataSize));
            curr += keySize+dataSize;
        }
        List<Object> childs = null;
        if(childNum > 0){
            childs = new ArrayList<Object>(childNum);
            for (int i = 0; i < childNum; i++) {
                childs.add(TypeUtil.bytesToLong(Arrays.copyOfRange(body,curr, curr + 8)));
                curr += 8;
            }
        }
        BNode bnode = new BNode();
        bnode.page = page;
        bnode.keySize = keySize;
        bnode.dataSize = dataSize;
        bnode.childIsLeaf = childIsLeaf;
        bnode.keyvalues = keyvalues;
        bnode.childs = childs;
        return bnode;
    }

    private void flushBnodeIntoDisk(BNode bNode)throws IOException{
        if(pageMgr == null) return;
        int len = 6 + bNode.keyvalues.size()*(bNode.keySize+bNode.dataSize) + (bNode.childs != null ? bNode.childs.size() * 8 : 0 );
        ByteArrayOutputStream out = new ByteArrayOutputStream(len);
        out.write(TypeUtil.shortToBytes((short)bNode.keyvalues.size()));
        byte[] childNum =  TypeUtil.shortToBytes((short)(bNode.childs != null ? bNode.childs.size():0));
        if(bNode.childs != null && bNode.childs.size() > 0 && bNode.childs.get(0) instanceof Long){
            childNum[0] = (byte) (childNum[0] | 0x80);
        }
        out.write(childNum);
        out.write(new byte[]{bNode.keySize,bNode.dataSize});
        for (byte[] keyvalue : bNode.keyvalues){
            out.write(keyvalue);
        }
        if(bNode.childs != null){
            for (Object object : bNode.childs){
                if(object instanceof BNode){
                    BNode tmp = (BNode)object;
                    out.write(TypeUtil.longToBytes(tmp.page.getStartIndex()));
                }else {
                    out.write(TypeUtil.longToBytes((Long)object));
                }
            }
        }
        bNode.page.flushIntoDisk(out.toByteArray());
    }
    public void flushBTree()throws IOException{
        if(pageMgr == null) return;
        pageMgr.firstAllocatedPos = root.page.getStartIndex();
        pageMgr.flushFirstPage();
        ArrayDeque<Object>  nodeStack = new ArrayDeque<Object>(1024);
        nodeStack.push(root);
        while (nodeStack.size() > 0){
            BNode curr = (BNode) nodeStack.pop();
            flushBnodeIntoDisk(curr);
            if(curr.childs == null || curr.childs.size() == 0 || curr.childs.get(0) instanceof Long) continue;
            //广度优先好了
            nodeStack.addAll(curr.childs);
        }
        for (BNode leaf : leafCache.values()){
            flushBnodeIntoDisk(leaf);
        }
    }

    //tools
    //根据key列表找出分路的子节点索引,注意,跟二分查找类似，但是不一样
    public int findChildIndex(List<byte[]> keyvalues, byte[] key, int keySize){
        int start = 0,end = keyvalues.size()-1;
        while (true){
            try {
                if(start == end){
                    int res = compare(key,keyvalues.get(start),keySize);
                    return  res <= 0 ? start : start + 1;
                }
                int mid = (start+end)/2;
                int res = compare(key, keyvalues.get(mid), keySize);
                if(res == 0) return mid;

                if(res < 0){
                    end = mid; //注意不是mid -1
                }else {
                    start = mid + 1; //注意是mid+1
                }
            }catch (Exception e){
                PrintUtil.print("start:%d end:%d size:%d", start, end, keyvalues.size());
                e.printStackTrace();
                System.exit(1);
            }

        }
    }

    public int compare(byte[] key1,byte[] key2,int len){
        for (int i = 0; i < len; i++) {
            if(key1[i] < key2[i]) return -1;
            if(key1[i] > key2[i]) return 1;
        }
        return 0;
    }

    private void print(String format,Object... args){
        if(debug){
            System.out.println(String.format(format,args));
        }
    }



    //TODO 支持前缀的范围查找
    public List<byte[]> queryByPrefix(byte[] from, byte[] to)throws IOException{
        //用队列保存,注意首尾项的筛选即可
        if(root == null) return  new ArrayList<byte[]>();
        if(from.length > root.keySize || to.length > root.keySize){
            throw new RuntimeException(String.format("查找的key长度大于索引key长度 %d > %d or %d > %d",from.length, root.keySize, to.length, root.keySize));
        }
        //如果根节点就是叶子节点
        if(root.childs == null) return getResultByRange(root, from, to);

        Object left = root, right= root;
        ArrayDeque<BNode> mids = new ArrayDeque<BNode>();
        List<BNode>  midLeafs = new ArrayList<BNode>();
        while (left == right){
            if(left instanceof Long){
                //已经是叶子节点
                return getResultByRange(leafCache.get(left),from,to);
            }
            BNode currNode = (BNode)left;
            int start = findChildIndex(currNode.keyvalues,from,from.length);
            if(start > currNode.keyvalues.size() - 1){
                left = right = currNode.childs.get(currNode.childs.size()-1);
                continue;
            }
            while (start >= 0){
                if(compare(from,currNode.keyvalues.get(start),from.length) == 0){
                    start--;
                }else {
                    break;
                }
            }
            if(start < 0 || compare(from,currNode.keyvalues.get(start),from.length) > 0){
                start++;
            }
            int end = findChildIndex(currNode.keyvalues,to ,to.length);;
            if(end > currNode.keyvalues.size() - 1){
                //do nothing
            }else {
                while (end >= 0){
                    if(compare(to, currNode.keyvalues.get(end),to.length) == 0){
                        end--;
                    }else {
                        break;
                    }
                }
                if(end < 0 || compare(to,currNode.keyvalues.get(end), to.length) > 0){
                    end ++;
                }
            }

        }

        return null;
    }
}
