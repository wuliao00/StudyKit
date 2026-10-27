Add-Type -AssemblyName System.Drawing

function Add-Annotation {
    param(
        [string]$Source,
        [string]$Target,
        [string]$Label,
        [int]$LabelX,
        [int]$LabelY,
        [int]$ArrowX1,
        [int]$ArrowY1,
        [int]$ArrowX2,
        [int]$ArrowY2,
        [string]$RedactRect = ""
    )
    $bmp = [System.Drawing.Bitmap]::FromFile((Resolve-Path $Source))
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

    if ($RedactRect -ne "") {
        $parts = $RedactRect.Split(",")
        $rect = [System.Drawing.Rectangle]::new([int]$parts[0], [int]$parts[1], [int]$parts[2], [int]$parts[3])
        $bg = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(255, 244, 241, 234))
        $g.FillRectangle($bg, $rect)
        $bg.Dispose()
        $penR = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(255, 140, 130, 118), 2)
        $g.DrawRectangle($penR, $rect)
        $penR.Dispose()
        $fontR = [System.Drawing.Font]::new("Microsoft YaHei", 22, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
        $brushR = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(255, 140, 130, 118))
        $g.DrawString("隐私信息已隐藏", $fontR, $brushR, [float]($rect.X + 18), [float]($rect.Y + 10))
        $fontR.Dispose(); $brushR.Dispose()
    }

    $orange = [System.Drawing.Color]::FromArgb(255, 228, 87, 46)
    $pen = [System.Drawing.Pen]::new($orange, 9)
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $g.DrawLine($pen, $ArrowX1, $ArrowY1, $ArrowX2, $ArrowY2)
    $pen.Dispose()

    $angle = [Math]::Atan2(($ArrowY2 - $ArrowY1), ($ArrowX2 - $ArrowX1))
    $head = 28
    $p1x = $ArrowX2 - $head * [Math]::Cos($angle - 0.5)
    $p1y = $ArrowY2 - $head * [Math]::Sin($angle - 0.5)
    $p2x = $ArrowX2 - $head * [Math]::Cos($angle + 0.5)
    $p2y = $ArrowY2 - $head * [Math]::Sin($angle + 0.5)
    $brush = [System.Drawing.SolidBrush]::new($orange)
    $points = [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new([float]$ArrowX2, [float]$ArrowY2),
        [System.Drawing.PointF]::new([float]$p1x, [float]$p1y),
        [System.Drawing.PointF]::new([float]$p2x, [float]$p2y)
    )
    $g.FillPolygon($brush, $points)

    if ($Label -eq "") {
        $g.Dispose()
        $bmp.Save($Target, [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
        return
    }

    $font = [System.Drawing.Font]::new("Microsoft YaHei", 34, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $size = $g.MeasureString($Label, $font)
    $pad = 16
    $labelRect = [System.Drawing.RectangleF]::new(
        [float]($LabelX - $pad), [float]($LabelY - $pad),
        [float]($size.Width + 2 * $pad), [float]($size.Height + 2 * $pad)
    )
    $bgBrush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(235, 255, 252, 247))
    $g.FillRectangle($bgBrush, $labelRect)
    $border = [System.Drawing.Pen]::new($orange, 3)
    $g.DrawRectangle($border, $labelRect.X, $labelRect.Y, $labelRect.Width, $labelRect.Height)
    $textBrush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(255, 162, 54, 25))
    $g.DrawString($Label, $font, $textBrush, [float]$LabelX, [float]$LabelY)

    $bgBrush.Dispose(); $border.Dispose(); $textBrush.Dispose(); $brush.Dispose(); $font.Dispose()
    $g.Dispose()
    $bmp.Save($Target, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

New-Item -ItemType Directory -Force -Path "docs/tutorial" | Out-Null

Add-Annotation -Source "docs/screenshots/tutorial-design.png" -Target "docs/tutorial/v34-design.png" `
    -Label "入口：底部「设计」" -LabelX 520 -LabelY 2080 -ArrowX1 760 -ArrowY1 2140 -ArrowX2 380 -ArrowY2 2240

Add-Annotation -Source "docs/screenshots/tutorial-store.png" -Target "docs/tutorial/v34-store.png" `
    -Label "" -LabelX 0 -LabelY 0 -ArrowX1 760 -ArrowY1 1150 -ArrowX2 540 -ArrowY2 1235

Add-Annotation -Source "docs/screenshots/tutorial-photo.png" -Target "docs/tutorial/v34-photo.png" `
    -Label "" -LabelX 0 -LabelY 0 -ArrowX1 780 -ArrowY1 1140 -ArrowX2 610 -ArrowY2 1345 `
    -RedactRect "360,610,600,150"

Add-Annotation -Source "docs/screenshots/v34-store-preview.png" -Target "docs/tutorial/v34-preview.png" `
    -Label "" -LabelX 0 -LabelY 0 -ArrowX1 760 -ArrowY1 2050 -ArrowX2 540 -ArrowY2 2235
