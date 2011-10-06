/**
 * Copyright 2010 JogAmp Community. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY JogAmp Community ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JogAmp Community OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and should not be interpreted as representing official policies, either expressed
 * or implied, of JogAmp Community.
 */

package jogamp.common.util.locks;

import java.util.List;
import java.util.concurrent.locks.AbstractOwnableSynchronizer;

import com.jogamp.common.util.locks.RecursiveLock;

/**
 * Reentrance locking toolkit, impl a non-complete fair FIFO scheduler.
 * <p>
 * Fair scheduling is not guaranteed due to the usage of {@link Object#notify()},
 * however new lock-applicants will wait if queue is not empty for {@link #lock()} 
 * and {@link #tryLock(long) tryLock}(timeout>0).</p>
 * 
 * <p>
 * Sync object extends {@link AbstractOwnableSynchronizer}, hence monitoring is possible.</p>
 */
public class RecursiveLockImpl01Unfairish implements RecursiveLock {

    @SuppressWarnings("serial")
    private static class Sync extends AbstractOwnableSynchronizer {
        private Sync() {
            super();
        }
        private final Thread getOwner() {
            return getExclusiveOwnerThread();
        }
        private final void setOwner(Thread t) {
            setExclusiveOwnerThread(t);
        }
        private final void setLockedStack(Throwable s) {
            List<Throwable> ls = LockDebugUtil.getRecursiveLockTrace();
            if(s==null) {
                ls.remove(lockedStack);
            } else {
                ls.add(s);
            }            
            lockedStack = s;
        }
        // lock count by same thread
        private int holdCount = 0;
        // stack trace of the lock, only used if DEBUG
        private Throwable lockedStack = null;
        private int qsz = 0;
    }
    private Sync sync = new Sync();
        
    public RecursiveLockImpl01Unfairish() {
    }

    /**
     * Returns the Throwable instance generated when this lock was taken the 1st time
     * and if {@link com.jogamp.common.util.locks.Lock#DEBUG} is turned on, otherwise it returns always <code>null</code>.
     * @see com.jogamp.common.util.locks.Lock#DEBUG
     */
    public final Throwable getLockedStack() {
        synchronized(sync) {
            return sync.lockedStack;
        }
    }

    public final Thread getOwner() {
        synchronized(sync) {
            return sync.getOwner();
        }
    }

    public final boolean isOwner() {
        synchronized(sync) {
            return isOwner(Thread.currentThread());
        }
    }

    public final boolean isOwner(Thread thread) {
        synchronized(sync) {
            return sync.getOwner() == thread ;
        }
    }

    public final boolean isLocked() {
        synchronized(sync) {
            return null != sync.getOwner();
        }
    }

    public final boolean isLockedByOtherThread() {
        synchronized(sync) {
            return null != sync.getOwner() && Thread.currentThread() != sync.getOwner() ;
        }
    }

    public final int getHoldCount() {
        synchronized(sync) {
            return sync.holdCount;
        }
    }

    public final void validateLocked() {
        synchronized(sync) {
            if ( Thread.currentThread() != sync.getOwner() ) {
                if ( null == sync.getOwner() ) {
                    throw new RuntimeException(threadName(Thread.currentThread())+": Not locked: "+toString());
                }
                if(null!=sync.lockedStack) {
                    sync.lockedStack.printStackTrace();
                }
                throw new RuntimeException(Thread.currentThread()+": Not owner: "+toString());
            }
        }
    }

    public final void lock() {
        synchronized(sync) {
            try {
                if(!tryLock(TIMEOUT)) {
                    if(null!=sync.lockedStack) {
                        sync.lockedStack.printStackTrace();
                    }
                    throw new RuntimeException("Waited "+TIMEOUT+"ms for: "+toString()+" - "+threadName(Thread.currentThread()));
                }
            } catch (InterruptedException e) {
                throw new RuntimeException("Interrupted", e);
            }
        }
    }

    public final boolean tryLock(long timeout) throws InterruptedException {
        synchronized(sync) {
            final Thread cur = Thread.currentThread();
            if(TRACE_LOCK) {
                System.err.println("+++ LOCK 0 "+toString()+", cur "+threadName(cur));
            }
            if (sync.getOwner() == cur) {
                ++sync.holdCount;
                if(TRACE_LOCK) {
                    System.err.println("+++ LOCK XR "+toString()+", cur "+threadName(cur));
                }
                return true;
            }
    
            if ( sync.getOwner() != null || ( 0<timeout && 0<sync.qsz ) ) {
    
                if ( 0 >= timeout ) {
                    // locked by other thread and no waiting requested
                    return false;
                }
    
                ++sync.qsz;
                do {
                    final long t0 = System.currentTimeMillis();
                    sync.wait(timeout);
                    timeout -= System.currentTimeMillis() - t0;
                } while (null != sync.getOwner() && 0 < timeout) ;
                --sync.qsz;
    
                if( 0 >= timeout ) {
                    // timed out
                    if(TRACE_LOCK || DEBUG) {
                        System.err.println("+++ LOCK XX "+toString()+", cur "+threadName(cur)+", left "+timeout+" ms");
                    }
                    return false;
                }
    
                ++sync.holdCount;
                if(TRACE_LOCK) {
                    System.err.println("+++ LOCK X1 "+toString()+", cur "+threadName(cur)+", left "+timeout+" ms");
                }
            } else {
                ++sync.holdCount;
                if(TRACE_LOCK) {
                    System.err.println("+++ LOCK X0 "+toString()+", cur "+threadName(cur));
                }
            }
    
            sync.setOwner(cur);
            if(DEBUG) {
                sync.setLockedStack(new Throwable("Previously locked by "+toString()));
            }
            return true;
        }
    }
    

    public final void unlock() {
        synchronized(sync) {
            unlock(null);
        }
    }

    public final void unlock(Runnable taskAfterUnlockBeforeNotify) {
        synchronized(sync) {
            validateLocked();
            final Thread cur = Thread.currentThread();
            
            --sync.holdCount;
            
            if (sync.holdCount > 0) {
                if(TRACE_LOCK) {
                    System.err.println("--- LOCK XR "+toString()+", cur "+threadName(cur));
                }
                return;
            }
            
            sync.setOwner(null);
            if(DEBUG) {
                sync.setLockedStack(null);
            }
            if(null!=taskAfterUnlockBeforeNotify) {
                taskAfterUnlockBeforeNotify.run();
            }
    
            if(TRACE_LOCK) {
                System.err.println("--- LOCK X0 "+toString()+", cur "+threadName(cur)+", signal any");
            }
            sync.notify();
        }
    }
    
    public final int getQueueLength() {
        synchronized(sync) {
            return sync.qsz;
        }
    }
    
    public String toString() {
        return syncName()+"[count "+sync.holdCount+
                           ", qsz "+sync.qsz+", owner "+threadName(sync.getOwner())+"]";
    }
    
    private final String syncName() {
        return "<"+Integer.toHexString(this.hashCode())+", "+Integer.toHexString(sync.hashCode())+">";
    }
    private final String threadName(Thread t) { return null!=t ? "<"+t.getName()+">" : "<NULL>" ; }
}
