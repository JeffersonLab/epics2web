var jlab = jlab || {};
jlab.epics2web = jlab.epics2web || {};

jlab.epics2web.doGet = function () {
    let input = $.trim($("#pvs-input").val());
    if (input !== '') {
        /*Replace all commas with space, split on any whitespace, filter out empty strings*/
        let tokens = input.replace(new RegExp(',', 'g'), " ").split(/\s/).filter(Boolean);

        let form = $('<form action="caget" method="get" style="display: none;"></form>');

        for (let i = 0; i < tokens.length; i++) {
            form.append('<input type="text" name="pv" value="' + tokens[i] + '"/>');
        }
        
        if($("#n").prop('checked')) {
            form.append('<input type="text" name="n" value="Y"/>');
        }

        form.appendTo('body').submit();
    }
};

$(document).on("click", "#caget-button", function () {
    jlab.epics2web.doGet();
    return false; /*Don't do default action*/
});

$(document).on("keyup", "#pvs-input", function (e) {
    if (e.keyCode === 13) {
        return false; /*Don't do default action*/
    }
});

