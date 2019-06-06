/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    private static final int INITIAL_CAPACITY;
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    private static final int LINK_CAPACITY;
    private static final int RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = safeFindNextPositivePowerOfTwo(SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
            }
        }

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);
    }

    private final int maxCapacityPerThread;
    private final int maxSharedCapacityFactor;
    private final int ratioMask;
    private final int maxDelayedQueuesPerThread;

    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    ratioMask, maxDelayedQueuesPerThread);
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        ratioMask = safeFindNextPositivePowerOfTwo(ratio) - 1;
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    @SuppressWarnings("unchecked")
    public final T get() {
        //如果允许缓存的个数是0 那么就采用不回收策略
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        //每一个线程有一个栈 专门来保存回收的对象
        Stack<T> stack = threadLocal.get();
        //获取对象的时候先从栈中取
        DefaultHandle<T> handle = stack.pop();
        if (handle == null) {
            //如果没有取到 就new一个 并新建一个handle 与此关联 handle指向一个对象
            handle = stack.newHandle();
            handle.value = newObject(handle);
        }
        //返回
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    protected abstract T newObject(Handle<T> handle);

    public interface Handle<T> {
        void recycle(T object);
    }

    static final class DefaultHandle<T> implements Handle<T> {
        private int lastRecycledId;
        private int recycleId;

        boolean hasBeenRecycled;

        private Stack<?> stack;
        private Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            Stack<?> stack = this.stack;
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException("recycled already");
            }
            //回收对象 将自己入栈
            stack.push(this);
        }
    }

    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    private static final class WeakOrderQueue {

        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            private final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            private int readIndex;
            Link next;
        }

        // This act as a place holder for the head Link but also will reclaim space once finalized.
        // Its important this does not hold any reference to either Stack or WeakOrderQueue.
        static final class Head {
            private final AtomicInteger availableSharedCapacity;

            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
            @Override
            protected void finalize() throws Throwable {
                try {
                    super.finalize();
                } finally {
                    Link head = link;
                    link = null;
                    while (head != null) {
                        reclaimSpace(LINK_CAPACITY);
                        Link next = head.next;
                        // Unlink to help GC and guard against GC nepotism.
                        head.next = null;
                        head = next;
                    }
                }
            }

            void reclaimSpace(int space) {
                assert space >= 0;
                availableSharedCapacity.addAndGet(space);
            }

            boolean reserveSpace(int space) {
                return reserveSpace(availableSharedCapacity, space);
            }

            static boolean reserveSpace(AtomicInteger availableSharedCapacity, int space) {
                assert space >= 0;
                for (;;) {
                    int available = availableSharedCapacity.get();
                    if (available < space) {
                        return false;
                    }
                    if (availableSharedCapacity.compareAndSet(available, available - space)) {
                        return true;
                    }
                }
            }
        }

        // chain of data items
        private final Head head;
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;
        private final WeakReference<Thread> owner;
        private final int id = ID_GENERATOR.getAndIncrement();

        private WeakOrderQueue() {
            owner = null;
            head = new Head(null);
        }

        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            //link加到尾部
            tail = new Link();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            //owner指向创建这个queue的线程
            owner = new WeakReference<Thread>(thread);
        }

        /**
         * 创建一个关联到stack的Queue
         * @param stack
         * @param thread
         * @return
         */
        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            //设置到stack关联queue的head
            stack.setHead(queue);

            return queue;
        }

        private void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        static WeakOrderQueue allocate(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            //看下这个stack还不能不能容下一个Link的大小 默认是16 cas替换成新值 因为是多线程操作
            return Head.reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY)
                    ? newQueue(stack, thread) : null;
        }

        void add(DefaultHandle<?> handle) {
            handle.lastRecycledId = id;
            //尾部取一个Link
            Link tail = this.tail;
            int writeIndex;
            //看看还有没有空间
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                //看下还是否允许分配 head里面保存这个是stack里面共享的剩余空间
                if (!head.reserveSpace(LINK_CAPACITY)) {
                    // Drop it.
                    return;
                }
                //分配成功就新建一个Link
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = new Link();
                //获取写入指针位置
                writeIndex = tail.get();
            }
            //加入元素
            tail.elements[writeIndex] = handle;
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            //将写指针加1
            tail.lazySet(writeIndex + 1);
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {

            //从head的readerIndex开始传输
            Link head = this.head.link;
            if (head == null) {
                return false;
            }
            //这个head已经读到头了
            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    return false;
                }
                this.head.link = head = head.next;
            }

            final int srcStart = head.readIndex;
            int srcEnd = head.get();
            //可读的数据
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }
            //原始的stack大小
            final int dstSize = dst.size;
            //新的大小
            final int expectedCapacity = dstSize + srcSize;
            //不够的话扩容size 达到最大限制就不扩容 传输失败
            if (expectedCapacity > dst.elements.length) {
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }
            //开始复制
            if (srcStart != srcEnd) {
                final DefaultHandle[] srcElems = head.elements;
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle element = srcElems[i];
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    srcElems[i] = null;
                    //同样采用1/8方法
                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    element.stack = dst;
                    //复制到栈中
                    dstElems[newDstSize ++] = element;
                }

                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    this.head.reclaimSpace(LINK_CAPACITY);
                    this.head.link = head.next;
                }

                head.readIndex = srcEnd;
                //没复制进去
                if (dst.size == newDstSize) {
                    return false;
                }
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }
    }

    static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        final WeakReference<Thread> threadRef;
        final AtomicInteger availableSharedCapacity;
        final int maxDelayedQueues;

        private final int maxCapacity;
        private final int ratioMask;
        private DefaultHandle<?>[] elements;
        private int size;
        private int handleRecycleCount = -1; // Start with -1 so the first one will be recycled.
        private WeakOrderQueue cursor, prev;
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int ratioMask, int maxDelayedQueues) {
            this.parent = parent;
            threadRef = new WeakReference<Thread>(thread);
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.ratioMask = ratioMask;
            this.maxDelayedQueues = maxDelayedQueues;
        }

        //将回收这个stack对象的queue连接起来
        // Marked as synchronized to ensure this is serialized.
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }

        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            int size = this.size;
            //如果当前stack的size为0 就去其他线程回收本线程的地方去回捞
            if (size == 0) {
                //从其他线程的queue中进行寻找
                if (!scavenge()) {
                    return null;
                }
                size = this.size;
            }
            //如果有 就是一个简单的出栈操作
            size --;
            DefaultHandle ret = elements[size];
            elements[size] = null;
            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            this.size = size;
            return ret;
        }

        boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) {
                return true;
            }

            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }

        boolean scavengeSome() {
            WeakOrderQueue prev;
            //上一次扫描的位置
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {
                prev = null;
                //第一次扫描就从头部开始
                cursor = head;
                if (cursor == null) {
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do {
                //尝试将queue里面的对象转移到stack中
                if (cursor.transfer(this)) {
                    //成功了就结束 下次从cursor开始
                    success = true;
                    break;
                }
                //没成功就开始下一个
                WeakOrderQueue next = cursor.next;
                if (cursor.owner.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    //如果这个Queue里面的引用的thread已经能不存在了
                    //看是否还有没读完的数据
                    if (cursor.hasFinalData()) {
                        for (;;) {
                            //全部传输过来
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    if (prev != null) {
                        //然后将当前queue移出stack链表
                        prev.setNext(next);
                    }
                } else {
                    prev = cursor;
                }

                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            //如果是在本线程回收
            if (threadRef.get() == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                //如果是在其他线程被回收
                pushLater(item, currentThread);
            }
        }

        private void pushNow(DefaultHandle<?> item) {
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;
            //如果说 已经大于了stack的最大size || 或者后面不是000(回收1/8)
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
            //如果已经达到capacity 则扩容2倍
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }
            //入栈
            elements[size] = item;
            this.size = size + 1;
        }

        //回收其他线程的对象
        private void pushLater(DefaultHandle<?> item, Thread thread) {
            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            //获取到自己线程的的Map key是别的线程的stack value 是自己线程回收的这个stack的对象
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            //取出指向stack的队列
            WeakOrderQueue queue = delayedRecycled.get(this);
            //如果为空 就初始化一个
            if (queue == null) {
                //如果指向别的线程栈的数量已经超过最大限制 那么就放入一个哨兵
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                if ((queue = WeakOrderQueue.allocate(this, thread)) == null) {
                    // drop object
                    return;
                }
                //放入这个queue
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                //创建的时候数量都都限制住了 遇到这种就不回收这个线程的对象了
                // drop object
                return;
            }
            //将这个handle添加到queue里面去
            queue.add(item);
        }

        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) {
                if ((++handleRecycleCount & ratioMask) != 0) {
                    // Drop the object.
                    return true;
                }
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
