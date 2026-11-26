import sys, json, time
sys.path.insert(0, '/tmp')
from mcp_client import init, call
init()

def texts(obs):
    return sorted({e.get("text") for e in obs.get("elements", []) if e.get("text")})

def center_of(e):
    for key in ("bounds", "rect", "boundsInScreen"):
        b = e.get(key)
        if b is None: continue
        if isinstance(b, dict):
            return ((b["left"] + b["right"]) // 2, (b["top"] + b["bottom"]) // 2)
        if isinstance(b, (list, tuple)) and len(b) == 4:
            return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
        if isinstance(b, str):
            import re
            nums = [int(n) for n in re.findall(r"-?\d+", b)]
            if len(nums) == 4:
                return ((nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2)
    return None

start = call("start_session", {"goal": "T-2 input tools proof"})
rh = start["runHandle"]
print("handle:", rh)
try:
    call("open_app", {"runHandle": rh, "appName": "Settings"})
    obs = None
    for _ in range(15):
        obs = call("observe", {"runHandle": rh})
        if obs.get("elements"): break
        time.sleep(1.0)
    before = texts(obs)
    print("before rows:", len(before))
    print("sample element:", json.dumps(obs["elements"][0] if obs.get("elements") else {}, ensure_ascii=False)[:300])
    r = call("swipe", {"runHandle": rh, "fromX": 460, "fromY": 820, "toX": 460, "toY": 320, "durationMs": 350})
    print("swipe ->", r.get("status"), r.get("error"))
    time.sleep(1.0)
    obs2 = call("observe", {"runHandle": rh})
    after = texts(obs2)
    print("after rows:", len(after))
    print("scroll diff: new=", [t for t in after if t not in before][:8], " gone=", [t for t in before if t not in after][:8])
    target = None
    for e in obs2.get("elements", []):
        if e.get("clickable") and e.get("text"):
            c = center_of(e)
            if c: target = (e["text"], c); break
    print("tap_at target:", target)
    if target:
        r = call("tap_at", {"runHandle": rh, "x": target[1][0], "y": target[1][1]})
        print("tap_at ->", r.get("status"), r.get("error"))
        time.sleep(1.5)
        obs3 = call("observe", {"runHandle": rh})
        print("after tap rows:", len(texts(obs3)), "| changed:", texts(obs3) != after)
    # T-3 spot checks (admission-level, no VD dependence)
    try:
        call("swipe", {"runHandle": rh, "fromX": 99999, "fromY": 10, "toX": 10, "toY": 10, "durationMs": 300})
        print("oob swipe: NOT refused (unexpected)")
    except Exception as ex:
        print("oob swipe refused:", str(ex)[:120])
finally:
    try: call("finish_run", {"runHandle": rh, "summary": "T-2 proof executed"})
    except Exception as ex: print("finish_run:", ex)
try:
    call("tap_at", {"runHandle": rh, "x": 10, "y": 10})
    print("stale handle: NOT refused (unexpected)")
except Exception as ex:
    print("stale handle refused:", str(ex)[:120])
print("done")
