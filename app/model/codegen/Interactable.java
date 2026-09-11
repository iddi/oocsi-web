
package model.codegen;

public class Interactable {

	public String type;
	public String name;
	public String par;
	public int min;
	public int max;
	public String def;

	public boolean isTrigger() {
		return "trigger".equalsIgnoreCase(type);
	}

	public boolean isButton() {
		return "button".equalsIgnoreCase(type);
	}

	public boolean isSlider() {
		return "slider".equalsIgnoreCase(type);
	}

	public String getDefault() {
		if (def == null) {
			return "0";
		}
		try {
			return String.valueOf(Integer.parseInt(def.trim()));
		} catch (NumberFormatException e) {
			return "0";
		}
	}

	public String getVarName() {
		if (par == null) {
			return "v_" + Math.abs((name != null ? name : "var").hashCode());
		}
		String clean = par.replaceAll("[^a-zA-Z0-9_]", "");
		if (clean.isEmpty() || Character.isDigit(clean.charAt(0))) {
			clean = "v_" + clean;
		}
		return clean;
	}
}
