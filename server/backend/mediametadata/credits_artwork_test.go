package mediametadata

import (
	"reflect"
	"strings"
	"testing"
)

func parseDeclarationFixture(t *testing.T, input string) Item {
	t.Helper()
	document, err := ParseNFO(strings.NewReader(input), Limits{})
	if err != nil {
		t.Fatal(err)
	}
	if len(document.Items) != 1 {
		t.Fatalf("want one item, got %d", len(document.Items))
	}
	return document.Items[0]
}

func TestCreditDeclarationsPreserveIdentityAndInputOrder(t *testing.T) {
	item := parseDeclarationFixture(t, `<movie>
  <actor clear="true">
    <name>测试演员</name><role>姐姐</role><role>旁白</role><order>10</order>
    <thumb>actors/portrait.jpg</thumb>
    <uniqueid type="tmdb">123</uniqueid><uniqueid type="custom-person">xyz</uniqueid>
    <id>unqualified-id</id><customfield>uninterpreted</customfield>
  </actor>
  <director>导演甲 / 导演乙</director>
  <writer>编剧甲</writer><credits>编剧乙</credits>
  <actor><name>测试演员</name><order>0</order></actor>
</movie>`)
	if len(item.Credits) != 5 {
		t.Fatalf("want five declarations: %#v", item.Credits)
	}
	actor := item.Credits[0]
	if actor.Kind != "actor" || actor.Tag != "actor" || actor.Attributes["clear"] != "true" {
		t.Fatalf("actor declaration changed: %#v", actor)
	}
	if !reflect.DeepEqual(actor.Fields["role"], []string{"姐姐", "旁白"}) || actor.Fields["order"][0] != "10" {
		t.Fatalf("roles/order lost: %#v", actor.Fields)
	}
	if actor.Fields["customfield"][0] != "uninterpreted" {
		t.Fatal("unknown scalar extension lost")
	}
	if len(actor.ExternalIDs) != 3 || actor.ExternalIDs[0].Provider != "tmdb" || actor.ExternalIDs[1].Provider != "custom-person" || actor.ExternalIDs[2].Provider != "" {
		t.Fatalf("explicit IDs must not be guessed or discarded: %#v", actor.ExternalIDs)
	}
	if len(actor.Artwork) != 1 || actor.Artwork[0].Role != "person" || actor.Artwork[0].Reference != "actors/portrait.jpg" {
		t.Fatalf("portrait declaration lost: %#v", actor.Artwork)
	}
	if len(item.ExternalIDs) != 0 || len(item.Artwork) != 0 {
		t.Fatal("person identity/artwork must not become work identity/artwork")
	}
	if item.Credits[1].Kind != "director" || item.Credits[1].Fields["name"][0] != "导演甲 / 导演乙" {
		t.Fatal("crew names must not be split by guessed separators")
	}
	if item.Credits[2].Kind != "writer" || item.Credits[3].Kind != "writer" || item.Credits[3].Tag != "credits" {
		t.Fatal("writer/credits source tag must be retained")
	}
	if item.Fields["director"][0] != "导演甲 / 导演乙" || item.Fields["writer"][0] != "编剧甲" || item.Fields["credits"][0] != "编剧乙" {
		t.Fatal("existing scalar crew contract changed")
	}
	if item.Credits[4].Fields["order"][0] != "0" || len(item.Credits[4].ExternalIDs) != 0 {
		t.Fatal("same-name credits must not be sorted, merged or inherit another person's ID")
	}
}

func TestCreditMissingEmptyUnknownAndNestedUserState(t *testing.T) {
	item := parseDeclarationFixture(t, `<episodedetails>
  <actor/>
  <actor><name/><role/><order>not-an-order</order><thumb/></actor>
  <actor><role>没有名字的角色</role><future-id>987</future-id>
    <watched>true</watched><playcount>1</playcount><lastplayed>yesterday</lastplayed>
    <userrating>8</userrating><resume><position>12</position></resume>
  </actor>
  <director/><writer><name>结构化编剧</name><role>改编</role><thumb aspect="future-type">writer.jpg</thumb></writer>
</episodedetails>`)
	if len(item.Credits) != 5 {
		t.Fatalf("missing declarations: %#v", item.Credits)
	}
	if _, exists := item.Credits[0].Fields["name"]; exists {
		t.Fatal("missing actor name was fabricated")
	}
	if !reflect.DeepEqual(item.Credits[1].Fields["name"], []string{""}) || !reflect.DeepEqual(item.Credits[1].Fields["role"], []string{""}) {
		t.Fatal("explicit empty fields lost")
	}
	if item.Credits[1].Fields["order"][0] != "not-an-order" || item.Credits[1].Artwork[0].Reference != "" {
		t.Fatal("unvalidated order/empty portrait must be preserved")
	}
	unknown := item.Credits[2]
	if _, exists := unknown.Fields["name"]; exists || len(unknown.ExternalIDs) != 0 || unknown.Fields["future-id"][0] != "987" {
		t.Fatal("unknown fields must not become a person ID or name")
	}
	for _, field := range []string{"watched", "playcount", "lastplayed", "userrating", "resume"} {
		if _, exists := unknown.Fields[field]; exists {
			t.Errorf("nested user state imported: %s", field)
		}
	}
	if !reflect.DeepEqual(item.Credits[3].Fields["name"], []string{""}) {
		t.Fatal("empty scalar director declaration lost")
	}
	writer := item.Credits[4]
	if writer.Fields["name"][0] != "结构化编剧" || writer.Artwork[0].Role != "" || writer.Artwork[0].Attributes["aspect"] != "future-type" {
		t.Fatal("structured writer or unknown image aspect changed")
	}
}

func TestArtworkDeclarationsRemainUnverifiedReferences(t *testing.T) {
	item := parseDeclarationFixture(t, `<movie>
  <thumb aspect="poster" preview="http://127.0.0.1/preview">../outside/poster.jpg</thumb>
  <thumb aspect="clearlogo">D:\Fixture\logo.png</thumb>
  <thumb aspect="Future-Art" preview="">plugin://untrusted/image</thumb>
  <thumb>https://example.invalid/not-a-person-id/123.jpg</thumb>
  <thumb aspect=""/>
  <fanart url="https://example.invalid/base/">
    <thumb preview="file:///unverified/preview">background.jpg</thumb>
    <thumb aspect="future-background">second.jpg</thumb>
  </fanart>
  <art><poster>poster-local.jpg</poster><fanart>backdrop-local.jpg</fanart><future-art>custom.jpg</future-art></art>
</movie>`)
	if len(item.Artwork) != 10 {
		t.Fatalf("want ten image declarations, got %#v", item.Artwork)
	}
	first := item.Artwork[0]
	if first.Role != "poster" || first.Reference != "../outside/poster.jpg" || first.Attributes["preview"] != "http://127.0.0.1/preview" {
		t.Fatalf("reference was resolved or altered: %#v", first)
	}
	if item.Artwork[1].Role != "logo" || item.Artwork[1].Reference != `D:\Fixture\logo.png` {
		t.Fatal("Windows path must remain source text")
	}
	if item.Artwork[2].Role != "" || item.Artwork[2].Attributes["aspect"] != "Future-Art" || item.Artwork[2].Reference != "plugin://untrusted/image" {
		t.Fatal("unknown artwork must not be reclassified or interpreted")
	}
	if preview, exists := item.Artwork[2].Attributes["preview"]; !exists || preview != "" {
		t.Fatal("explicit empty preview lost")
	}
	if item.Artwork[3].Role != "" || item.Artwork[4].Role != "" || item.Artwork[4].Reference != "" {
		t.Fatal("missing/empty aspect must not silently become a poster")
	}
	if _, exists := item.Artwork[3].Attributes["aspect"]; exists {
		t.Fatal("missing aspect became a field")
	}
	if _, exists := item.Artwork[4].Attributes["aspect"]; !exists {
		t.Fatal("explicit empty aspect lost")
	}
	backdrop := item.Artwork[5]
	if backdrop.Role != "backdrop" || backdrop.Container != "fanart" || backdrop.Reference != "background.jpg" || backdrop.ContainerAttributes["url"] != "https://example.invalid/base/" {
		t.Fatalf("fanart parent context lost or URL joined: %#v", backdrop)
	}
	if item.Artwork[6].Role != "" || item.Artwork[7].Role != "poster" || item.Artwork[8].Role != "backdrop" || item.Artwork[9].Role != "" {
		t.Fatal("art roles were inferred incorrectly")
	}
	if len(item.ExternalIDs) != 0 {
		t.Fatal("image URLs must never infer external IDs")
	}
}

func TestArtworkNestedExtensionsAreNotFlattenedIntoReferences(t *testing.T) {
	item := parseDeclarationFixture(t, `<movie>
  <thumb><name>not-a-reference</name></thumb>
  <fanart><thumb><name>not-a-reference</name></thumb></fanart>
  <art><poster><name>not-a-reference</name></poster></art>
  <art/><fanart/>
</movie>`)
	if len(item.Artwork) != 2 || item.Artwork[0].Tag != "art" || item.Artwork[1].Tag != "fanart" {
		t.Fatalf("structured extensions became references or empty containers disappeared: %#v", item.Artwork)
	}
	for _, artwork := range item.Artwork {
		if artwork.Reference != "" {
			t.Fatal("empty declaration fabricated a reference")
		}
	}
}

func TestCollectionDeclarationsStaySeparateFromWork(t *testing.T) {
	item := parseDeclarationFixture(t, `<movie>
  <set>旧式合集</set>
  <set><name>系列甲</name><overview/><uniqueid type="tmdb">1234</uniqueid><thumb aspect="poster">set-poster.jpg</thumb></set>
  <collection custom="uninterpreted"><name>系列甲</name><future-field>preserve-me</future-field><id>unqualified</id></collection>
  <set/>
  <collectionnumber>999</collectionnumber>
</movie>`)
	if len(item.Collections) != 4 {
		t.Fatalf("collection statements were merged: %#v", item.Collections)
	}
	if item.Collections[0].Fields["name"][0] != "旧式合集" || item.Collections[0].Tag != "set" {
		t.Fatal("legacy set name lost")
	}
	structured := item.Collections[1]
	if structured.Fields["name"][0] != "系列甲" || !reflect.DeepEqual(structured.Fields["overview"], []string{""}) || structured.ExternalIDs[0].Provider != "tmdb" {
		t.Fatalf("structured set lost: %#v", structured)
	}
	if structured.Artwork[0].Reference != "set-poster.jpg" || structured.Artwork[0].Container != "set" {
		t.Fatal("collection artwork association lost")
	}
	unknown := item.Collections[2]
	if unknown.Tag != "collection" || unknown.Attributes["custom"] != "uninterpreted" || unknown.Fields["future-field"][0] != "preserve-me" || unknown.ExternalIDs[0].Provider != "" {
		t.Fatalf("unknown collection evidence changed: %#v", unknown)
	}
	if _, exists := unknown.Fields["overview"]; exists {
		t.Fatal("missing overview must not become explicit empty")
	}
	if !reflect.DeepEqual(item.Collections[3].Fields["name"], []string{""}) || item.Fields["collectionnumber"][0] != "999" {
		t.Fatal("empty set or separate collectionnumber lost")
	}
	if len(item.ExternalIDs) != 0 || len(item.Artwork) != 0 || len(item.Collections[0].ExternalIDs) != 0 || len(item.Collections[3].ExternalIDs) != 0 {
		t.Fatal("collection identity/artwork was attached to the work or an arbitrary set")
	}
}

func TestNamespacedDeclarationsDoNotImpersonateSupportedFields(t *testing.T) {
	item := parseDeclarationFixture(t, `<movie xmlns:ext="urn:fixture">
  <ext:actor><ext:name>ignore</ext:name></ext:actor>
  <actor><ext:name>ignore</ext:name><name>保留</name><ext:thumb>ignore.jpg</ext:thumb></actor>
  <ext:thumb aspect="poster">ignore.jpg</ext:thumb><ext:set>ignore</ext:set>
</movie>`)
	if len(item.Credits) != 1 || !reflect.DeepEqual(item.Credits[0].Fields["name"], []string{"保留"}) || len(item.Credits[0].Artwork) != 0 || len(item.Artwork) != 0 || len(item.Collections) != 0 {
		t.Fatalf("namespaced extensions interpreted as standard fields: %#v", item)
	}
	item = parseDeclarationFixture(t, `<movie xmlns:ext="urn:fixture">
  <actor><ext:name>not-a-person-name</ext:name></actor>
  <director><ext:name>not-a-crew-name</ext:name></director>
  <set><ext:name>not-a-collection-name</ext:name></set>
  <thumb><ext:path>not-an-image-reference</ext:path></thumb>
  <fanart><thumb><ext:path>not-an-image-reference</ext:path></thumb></fanart>
  <art><ext:poster>not-an-image-reference</ext:poster><watched>true</watched></art>
</movie>`)
	for _, credit := range item.Credits {
		if _, exists := credit.Fields["name"]; exists {
			t.Fatalf("namespaced nested text inferred as a person name: %#v", credit)
		}
	}
	if _, exists := item.Collections[0].Fields["name"]; exists || len(item.Artwork) != 0 || len(item.Fields["director"]) != 0 {
		t.Fatalf("namespaced nested text inferred as a scalar declaration: %#v", item)
	}
}
