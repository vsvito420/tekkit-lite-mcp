-- cb: file transfer helper for the Claude Bridge (ComputerCraft 1.5)
-- cb get <file>  shows the file page by page (any key = next page)
-- cb put <file>  reads ":"-prefixed lines until "=<bytes> <sum>"
local a={...}
local function sum(s)
local c=0
for i=1,#s do c=(c+s:byte(i))%65536 end
return c
end
local function enc(s)
return (s:gsub("[^ -%[%]-~]",function(ch)
if ch=="\\" then return "\\\\" end
if ch=="\n" then return "\\n" end
if ch=="\t" then return "\\t" end
return string.format("\\%03d",ch:byte())
end))
end
local function dec(s)
local o,i={},1
while i<=#s do
local c=s:sub(i,i)
if c~="\\" then
o[#o+1]=c
i=i+1
else
local n=s:sub(i+1,i+1)
if n=="n" then o[#o+1]="\n" i=i+2
elseif n=="t" then o[#o+1]="\t" i=i+2
elseif n=="\\" then o[#o+1]="\\" i=i+2
else o[#o+1]=string.char(tonumber(s:sub(i+1,i+3))) i=i+4 end
end
end
return table.concat(o)
end
if a[1]=="get" then
local f=fs.open(a[2],"r")
if not f then print("CB NOFILE") return end
local d=f.readAll()
f.close()
local e=enc(d)
local w,h=term.getSize()
local cw=w-1
local per=cw*(h-1)
local pages=math.max(1,math.ceil(#e/per))
for p=1,pages do
term.clear()
term.setCursorPos(1,1)
term.write("CB "..p.."/"..pages.." "..#d.." "..sum(d))
local chunk=e:sub((p-1)*per+1,p*per)
for r=1,h-1 do
local row=chunk:sub((r-1)*cw+1,r*cw)
term.setCursorPos(1,r+1)
term.write(row.."|")
end
os.pullEvent("key")
end
term.clear()
term.setCursorPos(1,1)
print("CB DONE")
elseif a[1]=="put" then
local t={}
while true do
local l=read()
if l:sub(1,1)=="=" then
local data=table.concat(t,"\n")
local n,c=l:match("^=(%d+) (%d+)$")
if tonumber(n)==#data and tonumber(c)==sum(data) then
local f=fs.open(a[2],"w")
f.write(data)
f.close()
print("CB OK "..#data)
else
print("CB FAIL "..#data.." "..sum(data))
end
return
end
t[#t+1]=dec(l:sub(2))
end
else
print("cb get <file> | cb put <file>")
end
