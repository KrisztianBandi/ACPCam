-- Phone Camera v4 for Assetto Corsa (Custom Shaders Patch Lua app)
-- Phone pose (ARCore 6DoF) -> PC server -> this app -> camera. No overlay is drawn in the game.

local WS_URL    = 'ws://127.0.0.1:8765/ac'
local TIMEOUT   = 1.5      -- seconds without phone packets -> camera handed back to AC
local OWN_SHARE = 0.9999   -- <1 keeps AC's own camera mode running underneath
local FOV_SLEW  = 60       -- max FOV change in degrees per second
local SNAP_DIST = 5.0      -- car jumps further than this in one frame (replay seek / teleport) -> follow instantly

local clock = os.preciseClock or os.clock

-- latest phone state ------------------------------------------------------------
local S = { r={1,0,0}, u={0,0,1}, l={0,1,0}, fov=0, mode=0, rc=0, on=1, p={0,0,0}, track=1,
        scale={1,1,1}, shake=0.3, inv=0, anchorAc=0, ar=0, pshake=0.3, sens=1, hlock=0, level=0, carSmooth=0.5 }
local phoneConnected, lastPacket, pcServerSeen = false, -1000, false

-- camera state ----------------------------------------------------------------------
local cam
local yawS, yawC = 0, 1
local off = {0,0,0}                       -- car mode: offset in car frame from the car anchor. world mode: world position
local worldFrame = { s={1,0,0}, u={0,1,0}, l={0,0,1} }
local fLook, fUp, fTrack = {}, {}, {}
local curFov, lastFocus
local appliedRc, appliedMode, appliedLevel, appliedAnchor
local lastT = clock()
local status = 'Starting...'
local logged = false
local replay = false
-- car anchor (filtered car position)
local cp, cpInit = {0,0,0}, false
local mPrev, vEst = {0,0,0}, {0,0,0}
-- look scaling (continuous yaw)
local prevYaw, accYaw = nil, 0

-- vector helpers ------------------------------------------------------------------------
local function V(v) return { v.x, v.y, v.z } end
local function dot(a, b) return a[1]*b[1] + a[2]*b[2] + a[3]*b[3] end
local function cross(a, b) return { a[2]*b[3]-a[3]*b[2], a[3]*b[1]-a[1]*b[3], a[1]*b[2]-a[2]*b[1] } end
local function norm(a)
  local n = math.sqrt(dot(a, a))
  if n < 1e-9 then return { 0, 0, 1 } end
  return { a[1]/n, a[2]/n, a[3]/n }
end
local function comb(f, a)
  return { f.s[1]*a[1] + f.u[1]*a[2] + f.l[1]*a[3],
           f.s[2]*a[1] + f.u[2]*a[2] + f.l[2]*a[3],
           f.s[3]*a[1] + f.u[3]*a[2] + f.l[3]*a[3] }
end
local function bit_set(mask, i) return math.floor(mask / 2^i) % 2 == 1 end
local function wrap(a) while a > math.pi do a = a - 2*math.pi end while a < -math.pi do a = a + 2*math.pi end return a end

local function flatFrameFromLook(look)
  local l = { look[1], 0, look[3] }
  if l[1]*l[1] + l[3]*l[3] < 1e-6 then return nil end
  l = norm(l)
  return { s = { l[3], 0, -l[1] }, u = { 0, 1, 0 }, l = l }
end

-- car frame. side is ALWAYS cross(up, look) (car.side is never read).
-- level: ignore the car's pitch/roll (heading only) for a steady camera
local function carFrame(car, level)
  if level == 1 then
    local f = flatFrameFromLook(V(car.look))
    if f then return f end
  end
  local u, l = norm(V(car.up)), norm(V(car.look))
  return { s = cross(u, l), u = u, l = l }
end

local function toLocal(v)
  local E, N, U = v[1], v[2], v[3]
  return { E*yawC - N*yawS, U, E*yawS + N*yawC }
end

local function setHeadingRef()
  local hx, hy = S.l[1], S.l[2]
  if math.sqrt(hx*hx + hy*hy) < 0.3 then
    hx, hy = S.u[1], S.u[2]
    if S.l[3] > 0 then hx, hy = -hx, -hy end
  end
  local n = math.sqrt(hx*hx + hy*hy)
  if n < 1e-4 then return end
  yawS, yawC = hx / n, hy / n
end

-- adaptive low-pass (One-Euro style): heavy smoothing when still, light when moving fast
local function alphaFor(dt, fc) return 1 / (1 + (1 / (2 * math.pi * fc)) / dt) end
local function smooth(st, target, dt, amount, betaHi, betaLo)
  if amount <= 0.001 or not st.v then
    st.v = { target[1], target[2], target[3] }; st.ds = 0
    return st.v
  end
  local sp2 = 0
  for i = 1, 3 do local d = (target[i] - st.v[i]) / dt; sp2 = sp2 + d*d end
  st.ds = st.ds + (math.sqrt(sp2) - st.ds) * alphaFor(dt, 1.0)
  local minCut = 0.1 + 25 * (1 - amount) ^ 3
  local beta = betaLo + (betaHi - betaLo) * (1 - amount) ^ 2
  local a = alphaFor(dt, minCut + beta * st.ds)
  for i = 1, 3 do st.v[i] = st.v[i] + (target[i] - st.v[i]) * a end
  return st.v
end

-- invert / look sensitivity / horizon lock
local function shapeLook(look, up)
  local iy, ip, ir = bit_set(S.inv, 0), bit_set(S.inv, 1), bit_set(S.inv, 2)
  local sens, lock = S.sens, S.hlock == 1
  if not (iy or ip or ir or lock or math.abs(sens - 1) > 0.001) then prevYaw = nil; return look, up end
  local yaw = math.atan2(look[1], look[3])
  local pitch = math.asin(math.max(-1, math.min(1, look[2])))
  local r0 = { math.cos(yaw), 0, -math.sin(yaw) }
  local l0 = { math.cos(pitch)*math.sin(yaw), math.sin(pitch), math.cos(pitch)*math.cos(yaw) }
  local u0 = cross(l0, r0)
  local roll = math.atan2(dot(up, r0), dot(up, u0))
  -- continuous yaw so sensitivity scaling has no jump when looking behind you
  if prevYaw == nil then accYaw = yaw else accYaw = accYaw + wrap(yaw - prevYaw) end
  prevYaw = yaw
  yaw = accYaw * sens * (iy and -1 or 1)
  pitch = math.max(-1.55, math.min(1.55, pitch * sens * (ip and -1 or 1)))
  roll = lock and 0 or roll * (ir and -1 or 1)
  local cp_ = math.cos(pitch)
  local nl = { cp_*math.sin(yaw), math.sin(pitch), cp_*math.cos(yaw) }
  local nr0 = { math.cos(yaw), 0, -math.sin(yaw) }
  local nu0 = cross(nl, nr0)
  local nu = { nu0[1]*math.cos(roll) + nr0[1]*math.sin(roll),
               nu0[2]*math.cos(roll) + nr0[2]*math.sin(roll),
               nu0[3]*math.cos(roll) + nr0[3]*math.sin(roll) }
  return nl, nu
end

local function moveSign(i) return bit_set(S.inv, 2 + i) and -1 or 1 end   -- bits 3,4,5 = X,Y,Z

-- car anchor: filtered car position. Prediction uses the car's real velocity when CSP provides it
-- (otherwise a smoothed estimate), and a small correction toward the measured position removes jitter.
local function carVelocity(car)
  local ok, v = pcall(function() return car.velocity end)
  if ok and v then
    local okc, x, y, z = pcall(function() return v.x, v.y, v.z end)
    if okc and type(x) == 'number' and x == x and y == y and z == z
       and math.abs(x) < 150 and math.abs(y) < 150 and math.abs(z) < 150 then
      return { x, y, z }
    end
  end
  return nil
end

local function updateAnchor(car, dt)
  local m = V(car.position)
  if S.carSmooth <= 0.001 or not cpInit then
    cp, mPrev, vEst, cpInit = m, m, {0,0,0}, true
    return
  end
  local v = carVelocity(car)
  if not v then
    local k = 1 - math.exp(-dt / 0.25)
    for i = 1, 3 do vEst[i] = vEst[i] + ((m[i] - mPrev[i]) / dt - vEst[i]) * k end
    v = vEst
  end
  mPrev = m
  for i = 1, 3 do cp[i] = cp[i] + v[i] * dt end
  local rx, ry, rz = m[1]-cp[1], m[2]-cp[2], m[3]-cp[3]
  if math.sqrt(rx*rx + ry*ry + rz*rz) > SNAP_DIST then cp, vEst = m, {0,0,0}; return end
  local alpha = 1 - 0.92 * S.carSmooth
  cp[1], cp[2], cp[3] = cp[1] + alpha*rx, cp[2] + alpha*ry, cp[3] + alpha*rz
end

local function anchorPos(acFlag)
  if acFlag == 1 then local p = cam.transformOriginal.position; return { p.x, p.y, p.z } end
  return { cp[1], cp[2], cp[3] }
end

local function worldPosNow(car)
  if appliedMode == 0 then
    local a, w = anchorPos(appliedAnchor), comb(carFrame(car, appliedLevel), off)
    return { a[1]+w[1], a[2]+w[2], a[3]+w[3] }
  end
  return { off[1], off[2], off[3] }
end

local function makeWorldFrame(car)
  local ol = V(cam.transformOriginal.look)
  return flatFrameFromLook(ol) or flatFrameFromLook(V(car.look)) or worldFrame
end

local function resetFilters() fLook, fUp, fTrack = {}, {}, {}; prevYaw = nil end

-- offset of AC's current camera relative to the car anchor, in the car frame
local function offsetFromWorld(car, wp)
  local a, f = anchorPos(S.anchorAc), carFrame(car, S.level)
  local d = { wp[1]-a[1], wp[2]-a[2], wp[3]-a[3] }
  return { dot(d, f.s), dot(d, f.u), dot(d, f.l) }
end

local function recenter(car)
  setHeadingRef()
  resetFilters()
  local p = cam.transformOriginal.position
  if S.mode == 0 then
    if S.anchorAc == 1 then off = { 0, 0, 0 }
    else off = offsetFromWorld(car, { p.x, p.y, p.z }) end     -- start exactly where AC's camera is, but attached to the car
  else
    worldFrame = makeWorldFrame(car)
    off = { p.x, p.y, p.z }
  end
  appliedMode, appliedRc, appliedLevel, appliedAnchor = S.mode, S.rc, S.level, S.anchorAc
end

local function rebase(car)   -- mode / level / anchor changed: keep the camera where it is in the world
  local wp = worldPosNow(car)
  if S.mode == 0 then off = offsetFromWorld(car, wp)
  else worldFrame = makeWorldFrame(car); off = wp end
  appliedMode, appliedLevel, appliedAnchor = S.mode, S.level, S.anchorAc
end

local function release()
  if cam then pcall(function() cam:dispose() end) cam = nil end
  appliedRc, appliedMode, appliedLevel, appliedAnchor, curFov, lastFocus, cpInit = nil, nil, nil, nil, nil, nil, false
end

-- networking --------------------------------------------------------------------------------
local function onMessage(data)
  data = tostring(data)
  pcServerSeen = true
  local tag = data:sub(1, 1)
  if tag == 'S' then
    phoneConnected = data:sub(3, 3) == '1'
    if not phoneConnected then lastPacket = -1000 end
  elseif tag == 'P' then
    local n, i = {}, 0
    for w in data:gmatch('%S+') do
      if i > 0 then n[i] = tonumber(w) or 0 end
      i = i + 1
    end
    if #n >= 29 then
      S.r, S.u, S.l = { n[1], n[2], n[3] }, { n[4], n[5], n[6] }, { n[7], n[8], n[9] }
      S.fov = n[10]
      S.mode, S.rc, S.on = math.floor(n[11] + 0.5), math.floor(n[12] + 0.5), math.floor(n[13] + 0.5)
      S.p = { n[14], n[15], n[16] }
      S.track = math.floor(n[17] + 0.5)
      S.scale = { n[18], n[19], n[20] }
      S.shake = math.max(0, math.min(1, n[21]))
      S.inv = math.floor(n[22] + 0.5)
      S.anchorAc = math.floor(n[23] + 0.5)
      S.ar = math.floor(n[24] + 0.5)
      S.pshake = math.max(0, math.min(1, n[25]))
      S.sens = math.max(0.1, math.min(3, n[26]))
      S.hlock = math.floor(n[27] + 0.5)
      S.level = math.floor(n[28] + 0.5)
      S.carSmooth = math.max(0, math.min(1, n[29]))
      phoneConnected = true
      lastPacket = clock()
    end
  end
end

web.socket(WS_URL, onMessage, {
  encoding = 'utf8', reconnect = true,
  onError = function() pcServerSeen = false; status = 'PC server not reachable' end,
})

-- per-frame camera update ------------------------------------------------------------------------
function script.frameBegin()
  local t = clock()
  local dt = math.max(math.min(t - lastT, 0.1), 1e-3)
  lastT = t

  local active = phoneConnected and S.on == 1 and (t - lastPacket) < TIMEOUT
  if not active then
    release()
    status = pcServerSeen and 'PC server OK - waiting for phone' or 'Waiting for PC server...'
    return
  end

  if not cam then
    local c, err = ac.grabCamera('Phone Camera')
    if not c then status = 'Camera busy: ' .. tostring(err); return end
    cam = c
    cam.ownShare = OWN_SHARE
    curFov = cam.fovOriginal
    logged = false
  end

  local sim = ac.getSim()
  local focus = sim.focusedCar
  local car = ac.getCar(focus) or ac.getCar(0)
  if not car then return end
  local okR, r = pcall(function() return sim.isReplayActive end)
  replay = okR and r == true

  if lastFocus ~= nil and lastFocus ~= focus then cpInit = false end
  updateAnchor(car, dt)

  if appliedRc == nil or appliedRc ~= S.rc or (S.mode == 0 and lastFocus ~= nil and lastFocus ~= focus) then
    recenter(car)
  elseif appliedMode ~= S.mode or appliedLevel ~= S.level or appliedAnchor ~= S.anchorAc then
    rebase(car)
  end
  lastFocus = focus

  -- orientation
  local lk = smooth(fLook, toLocal(S.l), dt, S.shake, 14, 1)
  local up = smooth(fUp,   toLocal(S.u), dt, S.shake, 14, 1)
  local look = norm(lk)
  local right = norm(cross(up, look))
  local upv = cross(look, right)
  look, upv = shapeLook(look, upv)

  -- tracked phone position, per-axis scale (0 disables an axis)
  local tr = { 0, 0, 0 }
  if S.track == 1 then
    tr = smooth(fTrack, toLocal(S.p), dt, S.pshake, 6, 0.5)
    tr = { tr[1] * S.scale[1] * moveSign(1), tr[2] * S.scale[2] * moveSign(2), tr[3] * S.scale[3] * moveSign(3) }
  end

  local f, pos
  if S.mode == 0 then
    f = carFrame(car, S.level)
    local a = anchorPos(S.anchorAc)
    local w = comb(f, { off[1] + tr[1], off[2] + tr[2], off[3] + tr[3] })
    pos = { a[1] + w[1], a[2] + w[2], a[3] + w[3] }
  else
    f = worldFrame
    local wt = comb(f, tr)
    pos = { off[1] + wt[1], off[2] + wt[2], off[3] + wt[3] }
  end

  local lw, uw = comb(f, look), comb(f, upv)
  local c = cam.transform
  c.position:set(pos[1], pos[2], pos[3])
  c.look:set(lw[1], lw[2], lw[3])
  c.up:set(uw[1], uw[2], uw[3])          -- side is derived by CSP from look+up

  local target = (S.fov >= 20) and S.fov or cam.fovOriginal
  target = math.max(15, math.min(120, target))
  curFov = curFov + math.max(-FOV_SLEW * dt, math.min(FOV_SLEW * dt, target - curFov))
  cam.fov = curFov

  if not logged then
    logged = true
    pcall(function() ac.log(string.format('PhoneCam: camera grabbed, AC fov %.1f', cam.fovOriginal)) end)
  end
  status = (S.mode == 0 and 'Active: car-locked' or 'Active: world anchor') .. (replay and ' (replay)' or '')
           .. (S.ar == 1 and ' | AR tracking' or (S.ar == 2 and ' | AR LOST' or ' | inertial'))
end

function script.windowMain(dt)
  ui.text('Phone Camera: ' .. status)
  ui.text('Phone connected: ' .. tostring(phoneConnected))
end
