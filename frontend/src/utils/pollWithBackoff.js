/**
 * 指数退避轮询：刚提交时问得勤，越往后问得越少，直到进入终态或总超时。
 * 1s -> 2s -> 4s -> 8s -> 8s ...（maxDelay 封顶）
 *
 * @param fetchStatus  每次轮询调用的函数，返回最新状态
 * @param isDone       判断是否终态；终态时停止并返回该结果（成功和失败终态都算，交给调用方区分）
 * @param shouldRetryError 轮询请求本身失败时是否继续（例如网络抖动）；返回 false 则直接抛出
 * @param onUpdate     每次拿到新状态时回调，用于更新界面
 * @param signal       AbortSignal，组件卸载时停止轮询
 * @param sleep        测试时注入
 *
 * @template T
 * @param {{
 *   fetchStatus: () => Promise<T>,
 *   isDone: (result: T) => boolean,
 *   shouldRetryError?: (error: unknown) => boolean,
 *   onUpdate?: (result: T) => void,
 *   initialDelay?: number,
 *   factor?: number,
 *   maxDelay?: number,
 *   timeout?: number,
 *   signal?: AbortSignal,
 *   now?: () => number,
 *   sleep?: (ms: number, signal?: AbortSignal) => Promise<void>
 * }} options
 * @returns {Promise<T>}
 */
export async function pollWithBackoff(options) {
  const {
    fetchStatus,
    isDone,
    shouldRetryError = () => false,
    onUpdate = () => {},
    initialDelay = 1000,
    factor = 2,
    maxDelay = 8000,
    timeout = 120000,
    signal,
    now = () => Date.now(),
    sleep = defaultSleep
  } = options;
  const deadline = now() + timeout;
  let delay = initialDelay;

  while (true) {
    throwIfAborted(signal);

    try {
      const result = await fetchStatus();
      onUpdate(result);
      if (isDone(result)) return result;
    } catch (error) {
      if (!shouldRetryError(error)) throw error;
    }

    // 下一次等待会超过总时限：停止，提示用户稍后再看，不让轮询永远转下去。
    if (now() + delay > deadline) throw new PollTimeoutError(timeout);

    await sleep(delay, signal);
    delay = Math.min(delay * factor, maxDelay);
  }
}

export class PollTimeoutError extends Error {
  constructor(timeout) {
    super(`Still processing after ${Math.round(timeout / 1000)} seconds`);
    this.name = "PollTimeoutError";
  }
}

function throwIfAborted(signal) {
  if (signal?.aborted) throw new DOMException("Polling aborted", "AbortError");
}

function defaultSleep(ms, signal) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener("abort", () => {
      clearTimeout(timer);
      reject(new DOMException("Polling aborted", "AbortError"));
    }, { once: true });
  });
}
