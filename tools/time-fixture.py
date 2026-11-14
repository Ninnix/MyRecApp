import pathlib, subprocess
root=pathlib.Path(__file__).resolve().parent.parent
times=[0,40000,110000,160000,300000,380000,500000,700000]
colors=[(220,30,25),(20,210,35),(30,40,220),(210,190,25),(200,30,180),(25,190,190),(130,80,35),(160,160,160)]
expr=str(times[-1])
for i in range(len(times)-2,-1,-1):expr=f'if(eq(N,{i}),{times[i]},{expr})'
subprocess.run(['ffmpeg','-v','error','-y','-f','rawvideo','-pixel_format','rgb24','-video_size','96x64','-framerate','25','-i','pipe:0','-vf',f'settb=1/1000000,setpts={expr.replace(",", chr(92)+",")}','-fps_mode','vfr','-enc_time_base','1:1000000','-c:v','libx264','-pix_fmt','yuv420p','-bf','2','-g','30','-video_track_timescale','1000000',str(root/'app/src/androidTest/assets/time-vfr.mp4')],input=b''.join(bytes(c)*96*64 for c in colors),check=True)
