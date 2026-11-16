package org.myrec.app

import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

object TextOwnershipTests {
    fun run(test: (String,()->Unit)->Unit) {
        fun captions(): Editor {
            val clip=Clip(path="voice.mp4",sourceOut=4.0)
            val editor=Editor(Project(clips=mutableListOf(clip)))
            SubtitlesDraft.attach(editor.project,"series",listOf(
                SubtitleWord(text="First",start=.2,end=.7),SubtitleWord(text="half",start=.7,end=1.4),
                SubtitleWord(text="Second",start=2.2,end=2.7),SubtitleWord(text="half",start=2.7,end=3.4)),setOf(clip.id))
            return editor
        }
        test("subtitle corrections are independent after a cut and undo restores both words") {
            val editor=captions();editor.cursor=2.0;editor.split()
            val work=SubtitlesDraft(editor);check(work.rows().size==2)
            work.correct(work.rows().last().id,"Independent words")
            check(work.rows().first().text=="First half" && work.rows().last().text=="Independent words")
            work.commit(editor);editor.undo()
            check(SubtitlesDraft(editor).rows().last().text=="Second half")
        }
        test("copied subtitle words own their identities and regrouping never merges separate audio") {
            val editor=captions();editor.selected.add(editor.project.clips.single().id);editor.copySelection()
            editor.cursor=4.0;editor.paste();val work=SubtitlesDraft(editor)
            check(work.rows().size==4);work.correct(work.rows().last().id,"Copy")
            check(work.rows().first().text=="First half")
            check(!work.groupingAvailable)
            work.project.clips.flatMap { it.effects }.forEach { it.values["subtitleTiming"]=work.project.subtitleTimingFingerprint() }
            work.regroup(1);check(work.rows().size==7)
            check(editor.project.clips[0].effects.single().subtitle!!.words.map { it.id }.intersect(editor.project.clips[1].effects.single().subtitle!!.words.map { it.id }.toSet()).isEmpty())
        }
        test("captions stay on sound when picture is unlinked and survive independent picture deletion") {
            val editor=captions();editor.selected.add(editor.project.clips.single().id);editor.unlink()
            val picture=editor.project.clips.single { it.video };val audio=editor.project.clips.single { !it.video }
            check(picture.effects.none { it.subtitle!=null } && audio.effects.single().values["subtitleOwner"]=="audio")
            editor.clearSelection();editor.selected.add(picture.id);editor.deleteSelection(false)
            check(editor.project.subtitleCues().map { it.text }==listOf("First half","Second half"))
            audio.start=1.0;check(abs(editor.project.subtitleCues().first().start-1.2)<1e-6)
        }
        test("overlapping sound assigns each subtitle once to the loudest eligible owner") {
            val quiet=Clip(path="quiet",sourceOut=3.0,gainDb=-12f)
            val loud=Clip(path="loud",sourceOut=3.0,gainDb=-1f)
            val project=Project(clips=mutableListOf(quiet,loud))
            SubtitlesDraft.attach(project,"series",listOf(SubtitleWord(text="Only once",start=.2,end=1.0)),setOf(quiet.id,loud.id))
            check(quiet.effects.isEmpty() && loud.effects.single().subtitle!!.text=="Only once")
            loud.gainDb=-60f;check(project.subtitleCues().isEmpty())
        }
        test("correcting or removing one silence-separated cue leaves the other cue unchanged") {
            val editor=captions();val work=SubtitlesDraft(editor);check(work.rows().size==2)
            val other=work.project.clips.single().effects.single().subtitle!!.words.drop(2)
            work.correct(work.rows().first().id,"First changed")
            check(work.rows().last().text=="Second half" && work.project.clips.single().effects.single().subtitle!!.words.takeLast(2)==other)
            work.remove(work.rows().first().id);check(work.rows().single().text=="Second half")
            work.project.clips.single().gainDb=-60f;check(work.rows().isEmpty())
        }
        test("caption correction and regrouping preserve the source end before a removed pause") {
            val clip=Clip(path="sound",sourceOut=4.0);val editor=Editor(Project(clips=mutableListOf(clip)))
            SubtitlesDraft.attach(editor.project,"pause",listOf(SubtitleWord(text="Before",start=.2,end=1.0),SubtitleWord(text="After",start=2.0,end=2.5)),setOf(clip.id))
            editor.setPauseReviews(mapOf(clip.id to PauseReview(before=0.0,after=0.0,analyzed=SourceInterval(0.0,4.0),candidates=listOf(PauseCandidate(raw=SourceInterval(1.0,2.0))))))
            val work=SubtitlesDraft(editor);work.correct(work.rows().first().id,"Changed")
            val words=work.project.clips.single().effects.single().subtitle!!.words
            check(words.first().end==1.0 && words.last().start==2.0)
            work.project.clips.single().effects.forEach { it.values["subtitleTiming"]=work.project.subtitleTimingFingerprint() }
            work.regroup(1)
            val grouped=work.project.clips.single().effects.flatMap { it.subtitle!!.words }
            check(grouped==words)
        }
        test("subtitle appearance changes preserve source words ownership and cancelled edits") {
            val editor=captions();val work=SubtitlesDraft(editor);val before=work.project.clips.single().effects.single().subtitle
            val style=work.appearance()!!;style.values["entrance"]="Wipe";style.values["fontIndex"]="1";style.values["color"]="#ff00ff"
            work.applyAppearance(style)
            val effect=work.project.clips.single().effects.single()
            check(effect.subtitle==before && effect.values["subtitleOwner"]=="audio" && effect.values["fontIndex"]=="1")
            check(editor.project.clips.single().effects.single().values["color"]!="#ff00ff")
        }
        test("regenerating a selected caption range keeps corrected words and appearance outside it") {
            val editor=captions();val clip=editor.project.clips.single();clip.effects.single().values["color"]="#00ff00"
            val before=clip.effects.single().subtitle!!.words.take(2)
            SubtitlesDraft.install(editor.project,listOf(listOf(SubtitleWord(text="Replacement",start=2.2,end=3.4))),2.0..4.0,setOf(clip.id))
            check(clip.effects.first().subtitle!!.words==before)
            check(clip.effects.last().subtitle!!.text=="Replacement" && clip.effects.all { it.values["color"]=="#00ff00" })
        }
        test("subtitle regrouping detects structural edits ignores appearance and returns after undo") {
            val editor=captions();check(SubtitlesDraft(editor).groupingAvailable)
            editor.change { editor.project.clips.single().gainDb=-3f };check(SubtitlesDraft(editor).groupingAvailable)
            editor.selected.add(editor.project.clips.single().id);editor.setRate(2.0)
            val stale=SubtitlesDraft(editor);check(!stale.groupingAvailable)
            val before=stale.rows();stale.regroup(1);check(stale.rows()==before)
            editor.undo();check(SubtitlesDraft(editor).groupingAvailable)
        }
        test("bookmark labels and identities follow source through cuts copies speed and undo") {
            val editor=Editor(Project(clips=mutableListOf(Clip(path="video",sourceOut=4.0))))
            editor.cursor=2.5;val id=editor.addBookmark("Rome")!!
            editor.cursor=2.0;editor.split();check(editor.project.resolvedBookmarks().single().id==id)
            editor.renameBookmark(id,"Colosseum");check(editor.project.resolvedBookmarks().single().mark.label=="Colosseum")
            editor.clearSelection();editor.selected.add(editor.project.clips.last().id);editor.setRate(2.0)
            check(abs(editor.project.resolvedBookmarks().single().time-2.25)<1e-6)
            editor.copySelection();editor.cursor=editor.project.duration;editor.paste()
            check(editor.project.resolvedBookmarks().map { it.id }.distinct().size==2)
            editor.removeBookmark(id);check(editor.project.resolvedBookmarks().size==1);editor.undo();check(editor.project.resolvedBookmarks().size==2)
        }
        test("gap bookmarks insert copy ripple rename and remove as independent undoable edits") {
            val clip=Clip(path="later",sourceOut=2.0,start=2.0)
            val editor=Editor(Project(clips=mutableListOf(clip)))
            editor.cursor=1.0;val id=editor.addBookmark("Gap")!!;check(editor.project.resolvedBookmarks().single().clip==null)
            editor.cursor=2.5;editor.insert(Clip(path="new",sourceOut=1.0))
            check(editor.project.bookmarks.single().time==1.0)
            editor.renameBookmark(id,"🌍".repeat(121));check(editor.project.bookmarks.single().label.codePointCount(0,editor.project.bookmarks.single().label.length)==120)
            editor.removeBookmark(id);check(editor.project.bookmarks.isEmpty());editor.undo();check(editor.project.bookmarks.single().id==id)
        }
        test("gap bookmarks copy once with a range and insertion shifts later project anchors") {
            val editor=Editor(Project(clips=mutableListOf(Clip(path="a",sourceOut=1.0),Clip(path="b",sourceOut=1.0,start=3.0))))
            editor.cursor=2.0;editor.addBookmark("Gap");editor.range=0.0..4.0;editor.copySelection()
            editor.clearSelection();editor.cursor=4.0;editor.paste()
            check(editor.project.bookmarks.map { it.time }.sorted()==listOf(2.0,6.0))
            editor.clearSelection();editor.cursor=.5;editor.insert(Clip(path="insert",sourceOut=1.0))
            check(editor.project.bookmarks.map { it.time }.sorted()==listOf(3.0,7.0))
        }
        test("closing a gap clamps a project bookmark and undo restores its time") {
            val editor=Editor(Project(clips=mutableListOf(Clip(path="a",sourceOut=1.0),Clip(path="b",sourceOut=1.0,start=3.0))))
            editor.cursor=2.0;val id=editor.addBookmark("Gap")!!;editor.closeGap()
            check(editor.project.bookmarks.single().time==1.0 && editor.project.bookmarks.single().id==id)
            editor.undo();check(editor.project.bookmarks.single().time==2.0)
        }
        test("held-frame bookmarks use local time and never duplicate the source recording marks") {
            val clip=Clip(path="hold",sourceOut=1.0,frozen=FrozenFrame(.5,3.0),marks=mutableListOf(.5))
            val editor=Editor(Project(clips=mutableListOf(clip)));editor.cursor=1.2;val id=editor.addBookmark("Still")!!
            editor.cursor=1.0;editor.split();val mark=editor.project.resolvedBookmarks().single()
            check(mark.id==id && abs(mark.time-1.2)<1e-6 && mark.sourceTime==.5)
        }
        test("all bundled SFNT faces expose their real names and valid variable weight ranges") {
            val folder=listOf(File("src/main/assets/title-fonts"),File("app/src/main/assets/title-fonts")).first { it.isDirectory }
            val files=folder.walkTopDown().filter { it.extension=="ttf" }.toList();check(files.size>=18)
            for (file in files) {
                val bytes=file.readBytes();val face=FontFile.faces(ByteBuffer.wrap(bytes)).single()
                check(face.index==0 && face.name.isNotBlank() && face.postScript.isNotBlank())
                if ("wght" in file.name) check(face.weights.size>2 && face.weight in face.weights) else check(face.weights.isEmpty())
                check(runCatching { FontFile.faces(ByteBuffer.wrap(bytes.copyOf(20))) }.isFailure)
            }
            val broken=ByteBuffer.allocate(16).putInt(0x74746366).putInt(0x10000).putInt(Int.MAX_VALUE).array()
            check(runCatching { FontFile.faces(ByteBuffer.wrap(broken)) }.isFailure)
        }
        test("every title look preserves text and outline and all motion directions finish cleanly") {
            for (look in TitleStyle.looks.values) {
                val fx=Effect(kind="Text",values=mutableMapOf("text" to "Roma 🌍 روما", "outlined" to "true","fontIndex" to "12"))
                TitleStyle.applyLook(fx,look);check(fx.values["text"]=="Roma 🌍 روما" && fx.values["outlined"]=="true" && "fontIndex" !in fx.values)
            }
            for (mode in listOf("Fade","Drift","Scale","Wipe")) for (direction in listOf("Up","Down","Left","Right")) {
                val fx=Effect(kind="Text",values=mutableMapOf("entrance" to mode,"exit" to mode,"inDirection" to direction,"outDirection" to direction))
                check(TitleMotion.pose(fx,.5,1.0)==TitleMotion.Pose())
                val a=TitleMotion.pose(fx,0.0,1.0);val b=TitleMotion.pose(fx,1.0,1.0)
                check(if (mode=="Wipe") (a.right-a.left)*(a.bottom-a.top)==0f && (b.right-b.left)*(b.bottom-b.top)==0f else a.opacity==0f && b.opacity==0f)
            }
        }
    }
}
