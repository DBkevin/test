param(
    [Parameter(Mandatory = $true)]
    [string]$ScreenshotPath,

    [Parameter(Mandatory = $true)]
    [string]$XmlPath,

    [Parameter(Mandatory = $true)]
    [string]$SpecPath,

    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Parse-Bounds {
    param([string]$Bounds)

    $match = [regex]::Match($Bounds, "\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
    if (-not $match.Success) {
        throw "Invalid bounds: $Bounds"
    }

    [pscustomobject]@{
        X1 = [int]$match.Groups[1].Value
        Y1 = [int]$match.Groups[2].Value
        X2 = [int]$match.Groups[3].Value
        Y2 = [int]$match.Groups[4].Value
        Width = [int]$match.Groups[3].Value - [int]$match.Groups[1].Value
        Height = [int]$match.Groups[4].Value - [int]$match.Groups[2].Value
    }
}

function Convert-HexToColor {
    param([string]$Hex)

    $clean = $Hex.TrimStart('#')
    if ($clean.Length -ne 6) {
        throw "Invalid color: $Hex"
    }

    $r = [Convert]::ToInt32($clean.Substring(0, 2), 16)
    $g = [Convert]::ToInt32($clean.Substring(2, 2), 16)
    $b = [Convert]::ToInt32($clean.Substring(4, 2), 16)
    [System.Drawing.Color]::FromArgb(255, $r, $g, $b)
}

function Resolve-AnnotationBounds {
    param(
        [xml]$Xml,
        $Annotation
    )

    if ($Annotation.PSObject.Properties.Name -contains "bounds" -and $Annotation.bounds) {
        return $Annotation.bounds
    }

    if ($Annotation.PSObject.Properties.Name -contains "xpath" -and $Annotation.xpath) {
        $node = Select-Xml -Xml $Xml -XPath $Annotation.xpath | Select-Object -First 1
        if ($node) {
            return $node.Node.GetAttribute("bounds")
        }
    }

    $allNodes = Select-Xml -Xml $Xml -XPath "//*"
    foreach ($entry in $allNodes) {
        $node = $entry.Node
        $text = $node.GetAttribute("text")
        $contentDesc = $node.GetAttribute("content-desc")
        $resourceId = $node.GetAttribute("resource-id")

        if ($Annotation.PSObject.Properties.Name -contains "text" -and $Annotation.text -and $text -eq $Annotation.text) {
            return $node.GetAttribute("bounds")
        }

        if ($Annotation.PSObject.Properties.Name -contains "contentDesc" -and $Annotation.contentDesc -and $contentDesc -eq $Annotation.contentDesc) {
            return $node.GetAttribute("bounds")
        }

        if ($Annotation.PSObject.Properties.Name -contains "resourceId" -and $Annotation.resourceId -and $resourceId -eq $Annotation.resourceId) {
            return $node.GetAttribute("bounds")
        }

        if ($Annotation.PSObject.Properties.Name -contains "containsText" -and $Annotation.containsText -and $text -like ("*" + $Annotation.containsText + "*")) {
            return $node.GetAttribute("bounds")
        }

        if ($Annotation.PSObject.Properties.Name -contains "containsContentDesc" -and $Annotation.containsContentDesc -and $contentDesc -like ("*" + $Annotation.containsContentDesc + "*")) {
            return $node.GetAttribute("bounds")
        }
    }

    return $null
}

if (-not (Test-Path $ScreenshotPath)) {
    throw "Screenshot not found: $ScreenshotPath"
}

if (-not (Test-Path $XmlPath)) {
    throw "XML not found: $XmlPath"
}

if (-not (Test-Path $SpecPath)) {
    throw "Spec not found: $SpecPath"
}

if (-not $OutputPath) {
    $base = [System.IO.Path]::GetFileNameWithoutExtension($ScreenshotPath)
    $dir = Split-Path -Parent $ScreenshotPath
    $OutputPath = Join-Path $dir ($base + ".annotated.png")
}

Add-Type -AssemblyName System.Drawing

$xmlBytes = [System.IO.File]::ReadAllBytes($XmlPath)
$xmlText = [System.Text.Encoding]::UTF8.GetString($xmlBytes)
$xml = New-Object System.Xml.XmlDocument
$xml.LoadXml($xmlText)

$specText = [System.IO.File]::ReadAllText($SpecPath, [System.Text.Encoding]::UTF8)
$spec = $specText | ConvertFrom-Json
$annotations = @($spec.annotations)
if ($annotations.Count -gt 0) {
    $annotations = @(
        $annotations | Sort-Object @{
            Expression = {
                if ($_.PSObject.Properties.Name -contains "order" -and $_.order -ne $null) {
                    [int]$_.order
                } else {
                    9999
                }
            }
        }, @{
            Expression = {
                if ($_.PSObject.Properties.Name -contains "label" -and $_.label) {
                    $_.label
                } else {
                    ""
                }
            }
        }
    )
}

$bitmap = [System.Drawing.Bitmap]::FromFile($ScreenshotPath)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias

$font = New-Object System.Drawing.Font("Microsoft YaHei UI", 22, [System.Drawing.FontStyle]::Bold)
$labelBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)

foreach ($annotation in $annotations) {
    $resolvedBounds = Resolve-AnnotationBounds -Xml $xml -Annotation $annotation
    if (-not $resolvedBounds) {
        Write-Warning ("Annotation not found: " + $annotation.label)
        continue
    }

    $rectData = Parse-Bounds -Bounds $resolvedBounds
    $colorHex = "#ff3b30"
    if ($annotation.PSObject.Properties.Name -contains "color" -and $annotation.color) {
        $colorHex = $annotation.color
    }

    $color = Convert-HexToColor -Hex $colorHex
    $lineWidth = 6
    if ($annotation.PSObject.Properties.Name -contains "lineWidth" -and $annotation.lineWidth) {
        $lineWidth = [single]$annotation.lineWidth
    }

    $pen = New-Object System.Drawing.Pen($color, $lineWidth)
    $fillBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(190, $color))

    $graphics.DrawRectangle($pen, $rectData.X1, $rectData.Y1, $rectData.Width, $rectData.Height)

    $labelText = $annotation.label
    if ([string]::IsNullOrWhiteSpace($labelText)) {
        $labelText = "annotation"
    }

    $labelSize = $graphics.MeasureString($labelText, $font)
    $labelX = $rectData.X1
    $labelY = [Math]::Max(0, $rectData.Y1 - [int]$labelSize.Height - 8)
    $labelRect = [System.Drawing.RectangleF]::new(
        [single]$labelX,
        [single]$labelY,
        [single]($labelSize.Width + 24),
        [single]($labelSize.Height + 8)
    )

    $graphics.FillRectangle($fillBrush, $labelRect)
    $graphics.DrawString($labelText, $font, $labelBrush, $labelX + 12, $labelY + 2)

    $pen.Dispose()
    $fillBrush.Dispose()
}

$bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)

$labelBrush.Dispose()
$font.Dispose()
$graphics.Dispose()
$bitmap.Dispose()

Write-Output ("Annotated image saved to: " + $OutputPath)
